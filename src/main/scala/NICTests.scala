package icenet

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.devices.tilelink.TLROM
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.unittest.{UnitTest, UnitTestIO}
import freechips.rocketchip.util.{LatencyPipe, TwoWayCounter}
import testchipip.TLHelper
import scala.math.max
import IceNetConsts._

class IceNicTestSendDriver(
    sendReqs: Seq[(Int, Int, Boolean)],
    sendData: Seq[BigInt])(implicit p: Parameters) extends LazyModule {
  val node = TLHelper.makeClientNode(
    name = "test-send-driver", sourceId = IdRange(0, 1))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO {
      val send = new IceNicSendIO
    })

    val (tl, edge) = node.out(0)
    val dataBits = tl.params.dataBits
    val beatBytes = dataBits / 8
    val byteAddrBits = log2Ceil(beatBytes)
    val lenBits = NET_LEN_BITS - 1
    val addrBits = NET_IF_WIDTH - NET_LEN_BITS

    val (s_start :: s_write_req :: s_write_resp ::
         s_send :: s_done :: Nil) = Enum(5)
    val state = RegInit(s_start)

    val sendReqVec = VecInit(sendReqs.map {
      case (addr, len, part) => Cat(part.B, len.U(lenBits.W), addr.U(addrBits.W))})

    val sendReqAddrVec = VecInit(sendReqs.map{case (addr, _, _) => addr.U(addrBits.W)})
    val sendReqCounts = sendReqs.map { case (_, len, _) => len / beatBytes }
    val sendReqCountVec = VecInit(sendReqCounts.map(_.U))
    val sendReqBase = VecInit((0 +: (1 until sendReqs.size).map(
      i => sendReqCounts.slice(0, i).reduce(_ + _))).map(_.U(addrBits.W)))
    val maxMemCount = sendReqCounts.reduce(max(_, _))
    val totalMemCount = sendReqCounts.reduce(_ + _)

    require(totalMemCount == sendData.size)

    val sendDataVec = VecInit(sendData.map(_.U(dataBits.W)))

    val reqIdx = Reg(UInt(log2Ceil(sendReqs.size).W))
    val memIdx = Reg(UInt(log2Ceil(totalMemCount).W))

    val outSend = TwoWayCounter(
      io.send.req.fire(), io.send.comp.fire(), sendReqs.size)

    val writeAddr = sendReqAddrVec(reqIdx) + (memIdx << byteAddrBits.U)
    val writeData = sendDataVec(sendReqBase(reqIdx) + memIdx)

    tl.a.valid := state === s_write_req
    tl.a.bits := edge.Put(
      fromSource = 0.U,
      toAddress = writeAddr,
      lgSize = byteAddrBits.U,
      data = writeData)._2
    tl.d.ready := state === s_write_resp

    io.send.req.valid := state === s_send
    io.send.req.bits := sendReqVec(reqIdx)
    io.send.comp.ready := outSend =/= 0.U

    io.finished := state === s_done && outSend === 0.U

    when (state === s_start && io.start) {
      reqIdx := 0.U
      memIdx := 0.U
      state := s_write_req
    }

    when (tl.a.fire()) { state := s_write_resp }

    when (tl.d.fire()) {
      memIdx := memIdx + 1.U
      state := s_write_req

      when (memIdx === (sendReqCountVec(reqIdx) - 1.U)) {
        memIdx := 0.U
        reqIdx := reqIdx + 1.U
        when (reqIdx === (sendReqs.size - 1).U) {
          reqIdx := 0.U
          state := s_send
        }
      }
    }

    when (io.send.req.fire()) {
      reqIdx := reqIdx + 1.U
      when (reqIdx === (sendReqs.size - 1).U) {
        reqIdx := 0.U
        state := s_done
      }
    }
  }
}

class IceNicTestRecvDriver(recvReqs: Seq[Int], recvData: Seq[BigInt])
    (implicit p: Parameters) extends LazyModule {

  val node = TLHelper.makeClientNode(
    name = "test-recv-driver", sourceId = IdRange(0, 1))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO {
      val recv = new IceNicRecvIO
    })

    val (tl, edge) = node.out(0)
    val dataBits = tl.params.dataBits
    val beatBytes = dataBits / 8
    val byteAddrBits = log2Ceil(beatBytes)

    val (s_start :: s_recv :: s_wait ::
         s_check_req :: s_check_resp :: s_done :: Nil) = Enum(6)
    val state = RegInit(s_start)

    val recvReqVec  = VecInit(recvReqs.map(_.U(NET_IF_WIDTH.W)))
    val recvDataVec = VecInit(recvData.map(_.U(dataBits.W)))

    val reqIdx = Reg(UInt(log2Ceil(recvReqs.size).W))
    val memIdx = Reg(UInt(log2Ceil(recvData.size).W))

    val outRecv = TwoWayCounter(
      io.recv.req.fire(), io.recv.comp.fire(), recvReqVec.size)

    tl.a.valid := state === s_check_req
    tl.a.bits := edge.Get(
      fromSource = 0.U,
      toAddress = recvReqVec.head + (memIdx << byteAddrBits.U),
      lgSize = byteAddrBits.U)._2
    tl.d.ready := state === s_check_resp

    io.recv.req.valid := state === s_recv
    io.recv.req.bits := recvReqVec(reqIdx)
    io.recv.comp.ready := outRecv =/= 0.U

    io.finished := state === s_done

    when (state === s_start && io.start) {
      reqIdx := 0.U
      memIdx := 0.U
      state := s_recv
    }

    when (io.recv.req.fire()) {
      reqIdx := reqIdx + 1.U
      when (reqIdx === (recvReqVec.size - 1).U) {
        reqIdx := 0.U
        state := s_wait
      }
    }

    when (state === s_wait && outRecv === 0.U) {
      state := s_check_req
    }

    when (state === s_check_req && tl.a.ready) {
      state := s_check_resp
    }

    when (state === s_check_resp && tl.d.valid) {
      memIdx := memIdx + 1.U
      state := s_check_req
      when (memIdx === (recvData.size - 1).U) {
        memIdx := 0.U
        state := s_done
      }
    }

    assert(!tl.d.valid || tl.d.bits.data === recvDataVec(memIdx),
      "IceNicTest: Received wrong data")
  }
}

class IceNicRecvTest(implicit p: Parameters) extends NICLazyModule {
  val recvReqs = Seq(0, 1440, 1456)
  // The 90-flit packet should be dropped
  val recvLens = Seq(180, 2, 90, 8)
  val testData = Seq.tabulate(280) { i => BigInt(i << 4) }
  val recvData = testData.take(182) ++ testData.drop(272)

  val recvDriver = LazyModule(new IceNicTestRecvDriver(recvReqs, recvData))
  val recvPath = LazyModule(new IceNicRecvPath)
  val xbar = LazyModule(new TLXbar)
  val mem = LazyModule(new TLRAM(
    AddressSet(0, 0x7ff), beatBytes = NET_IF_BYTES))

  val MEM_LATENCY = 32
  val RLIMIT_INC = 1
  val RLIMIT_PERIOD = 4
  val RLIMIT_SIZE = 8

  xbar.node := recvDriver.node
  xbar.node := recvPath.node
  mem.node := TLFragmenter(NET_IF_BYTES, maxAcquireBytes) :=
    TLHelper.latency(MEM_LATENCY, xbar.node)

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO)

    val gen = Module(new PacketGen(recvLens, testData))
    gen.io.start := io.start
    recvDriver.module.io.start := io.start
    recvPath.module.io.recv <> recvDriver.module.io.recv
    recvPath.module.io.in <> RateLimiter(
      gen.io.out, RLIMIT_INC, RLIMIT_PERIOD, RLIMIT_SIZE)
    io.finished := recvDriver.module.io.finished
  }
}

class IceNicRecvTestWrapper(implicit p: Parameters) extends UnitTest(20000) {
  val test = Module(LazyModule(new IceNicRecvTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}

class IceNicSendTest(implicit p: Parameters) extends LazyModule {
  val sendReqs = Seq(
    (2, 10, true),
    (17, 6, false),
    (24, 12, false))
  val sendData = Seq(
    BigInt("7766554433221100", 16),
    BigInt("FFEEDDCCBBAA9988", 16),
    BigInt("0123456789ABCDEF", 16),
    BigInt("FEDCBA9876543210", 16),
    BigInt("76543210FDECBA98", 16))

  val recvData = Seq(
    BigInt("9988776655443322", 16),
    BigInt("23456789ABCDBBAA", 16),
    BigInt("FEDCBA9876543210", 16),
    BigInt("00000000FDECBA98", 16))
  val recvKeep = Seq(0xFF, 0xFF, 0xFF, 0x0F)
  val recvLast = Seq(false, true, false, true)

  val sendPath = LazyModule(new IceNicSendPath)
  val rom = LazyModule(new TLROM(0, 64,
    sendData.flatMap(
      data => (0 until 8).map(i => ((data >> (i * 8)) & 0xff).toByte)),
    beatBytes = 8))

  rom.node := TLFragmenter(NET_IF_BYTES, 16) := TLBuffer() := sendPath.node

  val RLIMIT_INC = 1
  val RLIMIT_PERIOD = 0
  val RLIMIT_SIZE = 8

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO)

    val sendPathIO = sendPath.module.io
    val sendReqVec = VecInit(sendReqs.map { case (start, len, part) =>
      Cat(part.B, len.U(15.W), start.U(48.W))
    })
    val (sendReqIdx, sendReqDone) = Counter(sendPathIO.send.req.fire(), sendReqs.size)
    val (sendCompIdx, sendCompDone) = Counter(sendPathIO.send.comp.fire(), sendReqs.size)

    val started = RegInit(false.B)
    val requesting = RegInit(false.B)
    val completing = RegInit(false.B)

    sendPathIO.send.req.valid := requesting
    sendPathIO.send.req.bits := sendReqVec(sendReqIdx)
    sendPathIO.send.comp.ready := completing

    when (!started && io.start) {
      requesting := true.B
      completing := true.B
    }
    when (sendReqDone)  { requesting := false.B }
    when (sendCompDone) { completing := false.B }

    sendPathIO.rlimit.inc := RLIMIT_INC.U
    sendPathIO.rlimit.period := RLIMIT_PERIOD.U
    sendPathIO.rlimit.size := RLIMIT_SIZE.U

    val check = Module(new PacketCheck(recvData, recvKeep, recvLast))
    check.io.in <> sendPathIO.out
    io.finished := check.io.finished && !completing && !requesting
  }
}

class IceNicSendTestWrapper(implicit p: Parameters) extends UnitTest {
  val test = Module(LazyModule(new IceNicSendTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}

class IceNicTest(implicit p: Parameters) extends NICLazyModule {
  val sendReqs = Seq(
    (0, 128, true),
    (144, 160, false),
    (320, 64, false))
  val recvReqs = Seq(256, 544)
  val testData = Seq.tabulate(44)(i => BigInt(i << 4))

  val sendDriver = LazyModule(new IceNicTestSendDriver(sendReqs, testData))
  val recvDriver = LazyModule(new IceNicTestRecvDriver(recvReqs, testData))
  val sendPath = LazyModule(new IceNicSendPath)
  val recvPath = LazyModule(new IceNicRecvPath)
  val xbar = LazyModule(new TLXbar)
  val mem = LazyModule(new TLRAM(
    AddressSet(0, 0x1ff), beatBytes = NET_IF_BYTES))

  val NET_LATENCY = 64
  val MEM_LATENCY = 32
  val RLIMIT_INC = 1
  val RLIMIT_PERIOD = 0
  val RLIMIT_SIZE = 8

  xbar.node := sendDriver.node
  xbar.node := recvDriver.node
  xbar.node := sendPath.node
  xbar.node := recvPath.node
  mem.node := TLFragmenter(NET_IF_BYTES, maxAcquireBytes) :=
    TLHelper.latency(MEM_LATENCY, xbar.node)

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle with UnitTestIO)

    sendPath.module.io.send <> sendDriver.module.io.send
    recvPath.module.io.recv <> recvDriver.module.io.recv

    sendPath.module.io.rlimit.inc := RLIMIT_INC.U
    sendPath.module.io.rlimit.period := RLIMIT_PERIOD.U
    sendPath.module.io.rlimit.size := RLIMIT_SIZE.U

    recvPath.module.io.in <> LatencyPipe(sendPath.module.io.out, NET_LATENCY)

    sendDriver.module.io.start := io.start
    recvDriver.module.io.start := io.start
    io.finished := sendDriver.module.io.finished && recvDriver.module.io.finished

    val count_start :: count_up :: count_print :: count_done :: Nil = Enum(4)
    val count_state = RegInit(count_start)
    val cycle_count = Reg(UInt(64.W))
    val recv_count = Reg(UInt(1.W))

    when (count_state === count_start && sendPath.module.io.send.req.fire()) {
      count_state := count_up
      cycle_count := 0.U
      recv_count := 1.U
    }
    when (count_state === count_up) {
      cycle_count := cycle_count + 1.U
      when (recvPath.module.io.recv.comp.fire()) {
        recv_count := recv_count - 1.U
        when (recv_count === 0.U) { count_state := count_print }
      }
    }
    when (count_state === count_print) {
      printf("NIC test completed in %d cycles\n", cycle_count)
      count_state := count_done
    }
  }
}

class IceNicTestWrapper(implicit p: Parameters) extends UnitTest(50000) {
  val test = Module(LazyModule(new IceNicTest).module)
  test.io.start := io.start
  io.finished := test.io.finished
}
