package rv32cpu

import chisel3._
import common.Consts._

class Top(initFile: String = "", debug: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val exit = Output(Bool())  //表示 CPU 当前是否到达约定位置
    val gp = Output(UInt(WORD_LEN.W))  // 输出通用寄存器 x3 的当前值，用于表示测试结果
  })

  // 将日志开关传给 Core，默认关闭逐周期打印。
  val core = Module(new Core(debug = debug))
  // 测试可以指定程序镜像；空字符串表示不加载文件。
  val memory = Module(new Memory(initFile))

  core.io.imem <> memory.io.imem
  core.io.dmem <> memory.io.dmem
  io.exit := core.io.exit
  io.gp := core.io.gp
}
