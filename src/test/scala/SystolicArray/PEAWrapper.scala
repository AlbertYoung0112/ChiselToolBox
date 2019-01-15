package SystolicArray

import chisel3.iotesters.{AdvTester, ChiselFlatSpec, Driver}

import scala.util.Random

class PEAWrapperTests(c: PEArrayWrapper) extends AdvTester(c) {
  val dataIn = c.io.ioArray.map{peBundle => DecoupledSource(peBundle.in.data)}
  val weightIn = c.io.ioArray.map{peBundle => DecoupledSource(peBundle.in.weight)}
  val resultIn = c.io.ioArray.map{peBundle => DecoupledSource(peBundle.in.result)}
  val controlIn = c.io.ioArray.map{peBundle => DecoupledSource(peBundle.in.control)}
  //val dataOut = c.io.ioArray.map{peBundle => IrrevocableSink(peBundle.out.data)}
  //val weightOut = c.io.ioArray.map{peBundle => IrrevocableSink(peBundle.out.weight)}
  val resultOut = c.io.ioArray.map{peBundle => IrrevocableSink(peBundle.out.result)}
  //val controlOut = c.io.ioArray.map{peBundle => IrrevocableSink(peBundle.out.control)}
  val TEST_CYCLES = 10
  def writeDataAndWeight(data :Int, weight: Int) = {
    for (chan <- 0 until c.rows) {
      dataIn(chan).inputs.enqueue(data)
      takesteps(4)()
    }
    for (chan <- 0 until c.cols) {
      weightIn(chan).inputs.enqueue(weight)
      takesteps(4)()
    }
  }
  for(cycles <- 0 until TEST_CYCLES) {
    reg_poke(c.io.fifoReset, 1)
    takesteps(10)()
    reg_poke(c.io.fifoReset, 0)
    takestep()
    //val testData = List.tabulate(18)(n => if(n % 3 == 0) 1 else 0)
    val testData = List.tabulate(18)(n => Random.nextInt(10))
    val testWeight = List.fill(3)(1)
    val expectedResult = List.tabulate(15)(
      n => {
        var sum = 0
        for(i <- 0 until 3)
          sum += testData(i + n) * testWeight(i)
        sum
      })
    println("Data:" + testData)
    println("Weight:" + testWeight)
    println("Expected:" + expectedResult)
    dataIn(0).inputs.enqueue(0, testData(0), testData(1))
    takesteps(20)()
    for(i <- 2 until 18) {
      writeDataAndWeight(testData(i), testWeight((i - 2)% 3))
    }
    takesteps(20)()
    writeDataAndWeight(0,0)
    writeDataAndWeight(0, 0)
    for(chan <- 0 until c.rows) {
      // Clear the invalid leading result
      for(pre <- 0 until 3)
        resultOut(chan).outputs.dequeue()

      print(s"Result Channel $chan Get: ")
      while(resultOut(chan).outputs.nonEmpty) {
        print(resultOut(chan).outputs.dequeue() + " ")
      }
      print("\n")
    }
    /*
    val writeSequence = Random.shuffle(List.range(0, 6))
    for(chan <- writeSequence.indices) {
      val targetChan = writeSequence(chan) % 3
      if(writeSequence(chan) < 3) {
        dataIn(targetChan).inputs.enqueue((i + targetChan) % 3 + 1)
        //println("Data Channel " + targetChan + " <- " + ((targetChan + i) % 3 + 1))
      } else {
        weightIn(targetChan).inputs.enqueue((i + targetChan) % 3 + 1)
        //println("Weight Channel " + targetChan + " <- " + ((targetChan + i) % 3 + 1))
      }
      takesteps(1)()
      for(chan <- resultOut.indices) {
        if(resultOut(chan).outputs.nonEmpty && chan == 0) {
          println("Result Channel " + chan + "  " + resultOut(chan).outputs.dequeue())
        }
      }
      */
    /*
    for(chan <- dataIn.indices) {
      dataIn(chan).inputs.enqueue((i + chan) % 3 + 1)
      takesteps(1)()
    }
    for(chan <- weightIn.indices) {
      weightIn(chan).inputs.enqueue((i + chan) % 3 + 1)
      takesteps(1)()
    }
    */
    takesteps(5)()
  }
  takesteps(100)()
}

class PEAWrapperTester extends ChiselFlatSpec {
  behavior of "PEArray Wrapper"
  backends foreach { backend =>
    it should s"PEArrayWrapper $backend" in {
      Driver(
        () => new PEArrayWrapper(1, 3, 32, 2, 5), backend
      )(c => new PEAWrapperTests(c)) should be (true)
    }
  }
}
