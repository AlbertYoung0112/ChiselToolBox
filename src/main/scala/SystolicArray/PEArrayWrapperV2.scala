package SystolicArray

import chisel3._
import chisel3.util._
import chisel3.util.EnqIO

trait PEAWState {
  val STATE_WIDTH =       3

  val WEIGHT_CLEAR =      0
  val WEIGHT_QUEUE_FILL = 1
  val WEIGHT_REFRESH =    2
  val DATA_FLOW =         3
  val DATA_CLEAR =        4
}

// Todo: Add padding control(the module now is with padding).

class PEArrayWrapperV2(
                        val dataWidth: Int,
                        val weightWidth: Int,
                        val weightChanNum: Int,
                        val rows: Int,
                        val cols: Int,
                        val PEResultFIFODepth: Int,
                        val wrapFIFODepth: Int,
                        val chanFIFODepth: Int
                      ) extends Module with PEAWState {

  val resultWidth = dataWidth + weightWidth
  val io = IO(new Bundle{
    // Data
    val dataIn = DeqIO(UInt(dataWidth.W))
    val weightIn = Vec(weightChanNum, DeqIO(UInt(weightWidth.W)))
    val resultOut = Vec(rows, EnqIO(UInt(resultWidth.W)))
    // Control
    val weightUpdate = Input(Bool())
    val weightUpdateReady = Output(Bool())
    val kernelSizeX = Input(UInt(8.W))
    val kernelSizeY = Input(UInt(8.W))
    val strideX = Input(UInt(8.W))
    val strideY = Input(UInt(8.W))
    val flush = Input(Bool())
    val activeChan = Output(UInt(rows.W))
  })

  val PEA = Module(new PEArrayV2(
    rows = rows,
    cols = cols,
    dataWidth = dataWidth,
    weightWidth = weightWidth,
    resultFIFODepth = PEResultFIFODepth))
  val rowController = Module(new PEARowController(rows = rows, spikeAt = -1))
  val colController = Module(new PEAColController(cols = cols))

  val state = RegInit(WEIGHT_CLEAR.U(STATE_WIDTH.W))

  private val weightInQueueInput = List.fill(cols)(Wire(EnqIO(UInt(weightWidth.W))))
  val dataInQueueInput = Wire(EnqIO(UInt(dataWidth.W)))
  //val resultOutQueueInput = Wire(EnqIO(UInt(resultWidth.W)))
  val dataInQueue = Queue(dataInQueueInput, wrapFIFODepth)
  //val dataChanQueue = List.fill(rows)(Queue(dataInQueue, chanFIFODepth))
  private val weightInQueue = List.tabulate(cols)(col => Queue(weightInQueueInput(col), chanFIFODepth))
  private val resultOutQueue = List.tabulate(rows)(row => Queue(PEA.io.ioArray(row).out.result, wrapFIFODepth))
  //val resultOutQueue = Queue(resultOutQueueInput, wrapFIFODepth)
  //val resultChanQueue = List.tabulate(rows)(row => Queue(PEA.io.ioArray(row).out.result, chanFIFODepth))

  for(row <- 0 until rows) {
    io.resultOut(row).bits := resultOutQueue(row).bits
    io.resultOut(row).valid := resultOutQueue(row).valid
    resultOutQueue(row).ready := io.resultOut(row).ready
    //io.resultOut(row) <> resultOutQueue(row)
  }

  private val weightFlow = List.fill(cols)(Wire(Bool()))
  private val dataFlow = List.fill(rows)(Wire(Bool()))
  private val controlFlow = List.fill(rows)(Wire(Bool()))
  private val controlOutputSum = List.fill(cols)(Wire(Bool()))
  private val controlCalculate = List.fill(cols)(Wire(Bool()))
  private val controlClearSum = List.fill(cols)(Wire(Bool()))
  val firstFire = RegInit(false.B)
  private val resultAllReady = Cat(PEA.io.ioArray.map(_.out.result.ready)).andR()
  private val weightAllValid = Cat(PEA.io.ioArray.map(_.in.weight.valid)).andR()
  private val allChannelReady = dataInQueue.valid & weightAllValid & resultAllReady
  private val anyDataChannelFire = Cat(PEA.io.ioArray.map(_.in.data.fire())).orR()
  private val anyWeightChannelFire = Cat(PEA.io.ioArray.map(_.in.weight.fire())).orR()
  val weightFlowEnable = Mux(state === DATA_FLOW.U, allChannelReady, true.B)
  val dataFlowEnable = Mux(state === DATA_FLOW.U, allChannelReady, false.B)
  val weightRefreshPrev = RegNext(io.weightUpdate)
  private val weightRefreshReq = io.weightUpdate & !weightRefreshPrev
  private val weightRefreshDone = !io.weightUpdate & weightRefreshPrev
  val repeat = RegInit(false.B)

  val flowCounter = RegInit(0.U(5.W))           //  Todo: Parameterize the width

  // Data allocation
  val dataChanFlowCounter = Mem(cols, UInt(3.W))   // Todo: Parameterize the width
  val dataChanLastActive = RegInit(0.U(3.W))
  val activeDataChannel = List.fill(cols)(Wire(Bool()))
  val dataReallocateCounter = RegInit(0.U(3.W))

  val strideX = RegInit(1.U(3.W))               // Todo: Parameterize the width
  val kernelSizeX = RegInit((cols - 1).U(3.W))  // Todo: Parameterize the width
  val controlShift = Mux(flowCounter === 0.U, kernelSizeX - 1.U, flowCounter - 1.U)

  dataInQueueInput.bits := io.dataIn.bits
  dataInQueueInput.valid := Mux(state === DATA_FLOW.U, io.dataIn.valid, false.B)
  io.dataIn.ready := Mux(state === DATA_FLOW.U, dataInQueueInput.ready, false.B)

  def dataChannelEnq(cond: Bool) = {
    when(cond) {
      dataFlow.foreach(_ := true.B)
      weightFlow.foreach(_ := true.B)
      when(Mux(state === DATA_FLOW.U, PEA.io.ioArray.head.in.data.fire(), PEA.io.ioArray.head.in.data.ready)) {
        flowCounter := Mux(flowCounter === kernelSizeX - 1.U, 0.U, flowCounter + 1.U)
        enableAllControl(false)
        setAllControlFlow(true)
      } .otherwise {
        flowCounter := flowCounter
        disableAllControl(false)
        setAllControlFlow(false)
      }
      for(col <- 0 until cols) {
        controlCalculate(col) := col.U < kernelSizeX
      }
      for(col <- 0 until cols) {
        controlClearSum(col) := Mux(col.U === controlShift & (col.U <= kernelSizeX), true.B, false.B) & (!firstFire | col.U =/= kernelSizeX - 1.U)
        controlOutputSum(col) := Mux(col.U === controlShift & (col.U <= kernelSizeX), true.B, false.B) & (!firstFire | col.U =/= kernelSizeX - 1.U)
      }
      //controlClearSum(cols - 1) := Mux((cols - 1).U === controlShift & cols.U === kernelSizeX, true.B, false.B) & !firstFire
      //controlOutputSum(cols - 1) := Mux((cols - 1).U === controlShift & cols.U === kernelSizeX, true.B, false.B) & !firstFire
    } .otherwise {
      setAllDataFlow(false)
      setAllWeightFlow(false)
      setAllChannelControl(calculate = false, outputSum = false, clearSum = false)
      setAllControlFlow(false)
      disableAllControl(false)
    }
    cond
  }

  def setAllChannelControl(calculate: Boolean, outputSum: Boolean, clearSum: Boolean, force: Boolean=false) = {
    if(!force) {
      for (chan <- 0 until cols) {
        controlClearSum(chan) := clearSum.B & chan.U < kernelSizeX
        controlCalculate(chan) := calculate.B & chan.U < kernelSizeX
        controlOutputSum(chan) := outputSum.B & chan.U < kernelSizeX
      }
    } else {
      for (chan <- 0 until cols) {
        controlClearSum(chan) := clearSum.B
        controlCalculate(chan) := calculate.B
        controlOutputSum(chan) := outputSum.B
      }
    }
  }
  def setAllWeightFlow(flow: Boolean, force: Boolean=false) = {
    if(!force) {
      for (chan <- 0 until cols) {
        weightFlow(chan) := flow.B & chan.U < kernelSizeX
      }
    } else {
      weightFlow.foreach(_ := flow.B)
    }
  }
  def setAllDataFlow(flow: Boolean, force: Boolean=false) = {
    if(!force) {
      for(chan <- 0 until rows) {
        dataFlow(chan) := flow.B & chan.U < kernelSizeX
      }
    } else {
      dataFlow.foreach(_ := flow.B)
    }
  }
  def setAllControlFlow(flow: Boolean, force: Boolean=false) = {
    if(!force) {
      for (chan <- 0 until cols) {
        controlFlow(chan) := flow.B & chan.U < kernelSizeX
      }
    } else {
      controlFlow.foreach(_ := flow.B)
    }
  }
  def enableControl(chan: Int, force: Boolean = false) = {
    if(!force)
      PEA.io.ioArray(chan).in.control.valid := true.B & chan.U < kernelSizeX
    else
      PEA.io.ioArray(chan).in.control.valid := true.B
  }
  def disableControl(chan: Int, force: Boolean = false) = {
    PEA.io.ioArray(chan).in.control.valid := false.B
  }
  def enableAllControl(force: Boolean = false) = {
    //PEA.io.ioArray.foreach(_.in.control.valid := true.B)
    if(!force) {
      for (col <- 0 until cols) {
        PEA.io.ioArray(col).in.control.valid := col.U < kernelSizeX
      }
    } else {
      PEA.io.ioArray.foreach(_.in.control.valid := true.B)
    }
  }
  def disableAllControl(force: Boolean = false) = {
    PEA.io.ioArray.foreach(_.in.control.valid := false.B)
  }
  io.weightUpdateReady := state === WEIGHT_QUEUE_FILL.U

  when(state === WEIGHT_CLEAR.U) {
    repeat := false.B
    setAllWeightFlow(flow = true, force = true)
    when(Cat(weightInQueue.map(_.valid)).orR()) {
      flowCounter := 0.U
    } .otherwise {
      flowCounter := Mux(flowCounter === (cols - 1).U, 0.U, flowCounter + 1.U)
      when(flowCounter === (cols - 1).U) {
        state := WEIGHT_QUEUE_FILL.U
      }
    }

    setAllChannelControl(calculate = false, outputSum = false, clearSum = true, force = true)
    setAllControlFlow(true, force = true)
    enableAllControl(force = true)

    setAllDataFlow(false)
  } .elsewhen(state === WEIGHT_QUEUE_FILL.U) {
    when(weightRefreshDone) {
      state := WEIGHT_REFRESH.U
      strideX := io.strideX     // Todo: Check the strideX
      kernelSizeX := io.kernelSizeX // Todo: Check the kernelSizeX
      flowCounter := 0.U
      repeat := true.B
    } .otherwise {
      repeat := false.B
    }
    setAllChannelControl(calculate = false, outputSum = false, clearSum = true)
    setAllControlFlow(true)
    enableAllControl(false)
    setAllDataFlow(false)
    setAllWeightFlow(false)
  } .elsewhen(state === WEIGHT_REFRESH.U) {
    // Refresh the weight in the array
    when(flowCounter === kernelSizeX - 1.U) {
      state := DATA_FLOW.U
      flowCounter := 0.U
      firstFire := true.B
    } .elsewhen(weightAllValid) {
      flowCounter := Mux(flowCounter === (cols - 1).U, 0.U, flowCounter + 1.U)
    }

    when(weightAllValid) {
      // All weights are ready to fire.
      for(col <- 0 until cols) {
        when(col.U < flowCounter) {
          weightFlow(col) := true.B
        } .otherwise {
          weightFlow(col) := false.B
        }
      }
    } .otherwise {
      setAllWeightFlow(false)
    }
    setAllDataFlow(false)
    setAllChannelControl(calculate = true, outputSum = false, clearSum = false)
    setAllControlFlow(true)
    disableAllControl(false)
  } .elsewhen(state === DATA_FLOW.U) {
    when(weightRefreshReq) {
      state := DATA_CLEAR.U
      setAllDataFlow(false)
      setAllWeightFlow(false)
      setAllChannelControl(calculate = false, outputSum = false, clearSum = false)
      setAllControlFlow(false)
      disableAllControl(false)
      firstFire := false.B
    } .otherwise {
      dataChannelEnq(cond = dataInQueue.valid & resultAllReady)
      when(dataInQueue.valid & resultAllReady & flowCounter === kernelSizeX - 1.U) {
        firstFire := false.B
      }
    }
  } .elsewhen(state === DATA_CLEAR.U) {
    when(Cat(PEA.io.ioArray.map(_.out.data.valid)).orR() | flowCounter =/= 0.U) {
      dataChannelEnq(cond = resultAllReady)
    } .otherwise {
      state := WEIGHT_CLEAR.U
      setAllDataFlow(false)
      setAllWeightFlow(false)
      //setAllChannelControl(calculate = false, outputSum = false, clearSum = true)
      for(col <- 0 until cols - 1) {
        controlCalculate(col) := false.B
        controlOutputSum(col) := false.B
        controlClearSum(col) := false.B
        //disableControl(col)
      }
      controlCalculate(cols - 1) := false.B
      controlOutputSum(cols - 1) := true.B & kernelSizeX === cols.U
      controlClearSum(cols - 1) := false.B
      //enableControl(cols - 1)
      setAllControlFlow(true)
      enableAllControl(false)
    }
  } .otherwise {
    state := DATA_CLEAR.U
    firstFire := false.B
    setAllDataFlow(false)
    setAllWeightFlow(false)
    setAllChannelControl(calculate = false, outputSum = false, clearSum = true)
    setAllControlFlow(true)
    disableAllControl(false)
  }

  // Link the weight channel
  for(col <- 0 until cols) {
    PEA.io.ioArray(col).in.weight <> weightInQueue(col)
    PEA.io.ioArray(col).out.weight.ready := weightFlow(col) & weightFlowEnable
    PEA.io.ioArray(col).in.control.bits.outputSum := controlOutputSum(col)
    PEA.io.ioArray(col).in.control.bits.calculate := controlCalculate(col)
    PEA.io.ioArray(col).in.control.bits.clearSum := controlClearSum(col)
    //PEA.io.ioArray(col).in.control.valid := true.B
    PEA.io.ioArray(col).out.control.ready := controlFlow(col)
  }

  // Weight Repeat
  for(col <- 0 until cols) {
    weightInQueueInput(col).valid := Mux(repeat,
      weightInQueue(col).fire(), io.weightIn(col).valid & state === WEIGHT_QUEUE_FILL.U)
    weightInQueueInput(col).bits := Mux(repeat,
      weightInQueue(col).bits, io.weightIn(col).bits)
    io.weightIn(col).ready := Mux(repeat,
      false.B, weightInQueueInput(col).ready & state === WEIGHT_QUEUE_FILL.U
    )
  }
  for(row <- 0 until rows) {
    PEA.io.ioArray(row).in.data.bits := Mux(state === DATA_CLEAR.U & !dataInQueue.valid, 0.U(dataWidth.W), dataInQueue.bits)
    PEA.io.ioArray(row).in.data.valid := Mux(state === DATA_CLEAR.U & !dataInQueue.valid, false.B, dataInQueue.valid)
    PEA.io.ioArray(row).out.data.ready := dataFlow(row) //& activeDataChannel(row)
  }
  dataInQueue.ready := Cat(PEA.io.ioArray.map(_.in.data.ready)).orR()

  rowController.io.kernelSize := kernelSizeX
  rowController.io.stride := strideX
  rowController.io.flow := anyDataFlow
  rowController.io.outputEnable := state === DATA_FLOW.U | state === DATA_CLEAR.U | state === WEIGHT_CLEAR.U
  rowController.io.presetRequest := state === WEIGHT_QUEUE_FILL.U
  rowController.io.clear := state === WEIGHT_CLEAR.U
  for(chan <- 0 until rows) {
    activeDataChannel(chan) := rowController.io.active(chan)
  }
  colController.io.kernelSize := kernelSizeY
  colController.io.stride := strideY
  colController.io.outputEnable := anyDataFlow | state === WEIGHT_CLEAR.U
  for(col <- 0 until cols) {
    activeCol(col) := colController.io.active(col)
  }

  // Unused IO
  for(row <- 0 until rows) {
    //PEA.io.ioArray(row).in.result.enq(0.U(resultWidth.W))
    PEA.io.ioArray(row).in.result.valid := false.B
    PEA.io.ioArray(row).in.result.bits := 0.U(resultWidth.W)
  }
}

class PEARowController(
                    val rows: Int,
                    val spikeAt: Int
                    ) extends Module {
  val io = IO(new Bundle {
    val outputEnable = Input(Bool())
    val kernelSize = Input(UInt(3.W))
    val stride = Input(UInt(3.W))
    val flow = Input(Bool())

    val presetRequest = Input(Bool())
    val presetDone = Output(Bool())

    val clear = Input(Bool())

    val active = Vec(rows, Output(Bool()))
    val activeSpike = Vec(rows, Output(Bool()))
  })
  val rowFlowCounter:List[UInt] = List.fill(rows)(RegInit(1.U(3.W)))
  val nextActiveChannel = RegInit(0.U(3.W))
  val reallocateCounter = RegInit(0.U(3.W))

  /* Controller Presetting Logic */
  val presetRequestPrev = RegNext(io.presetRequest)
  val presetting = RegInit(false.B)
  val presetCounter = RegInit(0.U(3.W))
  private def preset(): UInt = {
    when(presetCounter < io.kernelSize) {
      /* Update the flow counter */
      for(chan <- 0 until rows) {
        when(nextActiveChannel === chan.U) {
          rowFlowCounter(chan) := 0.U
        } .elsewhen(rowFlowCounter(chan) =/= io.kernelSize) {
          rowFlowCounter(chan) := rowFlowCounter(chan) + 1.U
        }
      }
      reallocateCounter := Mux(reallocateCounter === io.stride - 1.U, 0.U, reallocateCounter + 1.U)
      nextActiveChannel := nextActiveChannel + io.stride -
        Mux(nextActiveChannel + io.stride >= io.kernelSize, io.kernelSize, 0.U)
    }
    presetCounter := presetCounter + 1.U
    presetCounter
  }

  private def step() = {
    for(chan <- 0 until rows) {
      when(reallocateCounter === io.stride - 1.U & nextActiveChannel === chan.U) {
        /* Refresh the flow counter and re-active the channel */
        rowFlowCounter(chan) := 0.U
      } .elsewhen(rowFlowCounter(chan) =/= io.kernelSize) {
        rowFlowCounter(chan) := rowFlowCounter(chan) + 1.U
      }
    }
    /* Update the reallocate counter and the channel to be actived */
    when(reallocateCounter === io.stride - 1.U) {
      reallocateCounter := 0.U
      nextActiveChannel := nextActiveChannel + io.stride -
        Mux(nextActiveChannel + io.stride >= io.kernelSize, io.kernelSize, 0.U)
    } .otherwise {
      reallocateCounter := reallocateCounter + 1.U
    }
  }

  io.presetDone := !presetting
  when(io.presetRequest & !presetRequestPrev) {
    presetting := true.B
    presetCounter := 0.U
    nextActiveChannel := 0.U
    reallocateCounter := 0.U
    rowFlowCounter.head := 0.U
    for (chan <- 1 until rows) {
      rowFlowCounter(chan) := io.kernelSize
    }
  }
  when(presetting) {
    when(rowFlowCounter.head =/= io.kernelSize - 1.U) {
      step()
    } .otherwise {
      presetting := false.B
    }
  }

  /* Run */
  when(!presetting & io.flow) {
    step()
  }
  for(chan <- 0 until rows) {
    io.active(chan) := (rowFlowCounter(chan) =/= io.kernelSize) & !presetting & io.outputEnable
  }

  /* Active Spike Generator */
  val activeSpike = List.fill(rows)(RegInit(false.B))
  //val activeSpike = List.fill(rows)(Wire(Bool()))
  for(chan <- 0 until rows) {
    when(!presetting) {
      when(io.flow & rowFlowCounter(chan) === (if(spikeAt >= 0) spikeAt.U else io.kernelSize - (-spikeAt).U)) {
        activeSpike(chan) := true.B
      } .elsewhen(io.flow | io.clear) {
        activeSpike(chan) := false.B
      }
    } .otherwise {
      activeSpike(chan) := false.B
    }
    io.activeSpike(chan) := activeSpike(chan) & io.outputEnable
  }
}

class PEAColController(
                      val cols: Int
                      ) extends Module {
  val io = IO(new Bundle{
    val kernelSize = Input(UInt(3.W))
    val stride = Input(UInt(3.W))
    val outputEnable = Input(Bool())

    val active = Vec(cols, Output(Bool()))
  })

  for(col <- 0 until cols) {
    io.active(col) := col.U < io.kernelSize & io.outputEnable
  }
}
