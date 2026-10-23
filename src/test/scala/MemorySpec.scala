package memoryspec

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import common.Consts._
import rv32cpu.Memory

class MemorySpec extends AnyFlatSpec with ChiselSim {
  private val initFile = java.nio.file.Paths.get(
    getClass.getResource("/memory/init.hex").toURI
  ).toString

  // 每次 simulate 创建独立 Memory，测试之间不共享写入结果。
  private def disableWrite(c: Memory): Unit = {
    c.io.dmem.wen.poke(MEN_X)
    c.io.dmem.wdata.poke(0.U)
    c.io.imem.addr.poke(0.U)
    c.io.dmem.addr.poke(0.U)
  }

  "Memory" should "M1 load instructions from HEX through both ports" in {
    simulate(new Memory(initFile)) { c =>
      disableWrite(c)
      // 文件按字节保存：addi x3,x0,5 和 addi x3,x3,7。
      for ((addr, word) <- Seq(0 -> "h00500193", 4 -> "h00718193")) {
        c.io.imem.addr.poke(addr.U)
        c.io.dmem.addr.poke(addr.U)
        c.io.imem.inst.expect(word.U)
        c.io.dmem.rdata.expect(word.U)
      }
    }
  }

  it should "M2 assemble little endian words and read without a clock edge" in {
    simulate(new Memory(initFile)) { c =>
      disableWrite(c)
      // 全程不 step：改变地址后，expect 读取组合逻辑稳定后的结果。
      // 两端口同时访问不同地址，验证读地址相互独立。
      c.io.imem.addr.poke(0.U)
      c.io.dmem.addr.poke(4.U)
      c.io.imem.inst.expect("h00500193".U)
      c.io.dmem.rdata.expect("h00718193".U)

      // 地址 1 的字节是 01、50、00、93，小端拼接为 0x93005001。
      // 这是字节存储器读窗口测试，不表示 CPU 支持非对齐指令。
      c.io.imem.addr.poke(1.U)
      c.io.dmem.addr.poke(2.U)
      c.io.imem.inst.expect("h93005001".U)
      c.io.dmem.rdata.expect("h81930050".U)

      c.io.imem.addr.poke(4.U)
      c.io.dmem.addr.poke(0.U)
      c.io.imem.inst.expect("h00718193".U)
      c.io.dmem.rdata.expect("h00500193".U)
    }
  }

  it should "M3 commit a 32 bit write only on a clock edge" in {
    simulate(new Memory(initFile)) { c =>
      disableWrite(c)
      c.io.dmem.wdata.poke("hA1B2C3D4".U)
      c.io.dmem.wen.poke(MEN_S)
      // 写使能拉高还不够：时钟边沿之前仍是已知的初始化值。
      c.io.imem.inst.expect("h00500193".U)
      c.io.dmem.rdata.expect("h00500193".U)
      c.clock.step(1)
      c.io.dmem.wen.poke(MEN_X)
      c.io.imem.inst.expect("hA1B2C3D4".U)
      c.io.dmem.rdata.expect("hA1B2C3D4".U)
      // 跨写入区域边界回读，同时检查写入的字节顺序。
      c.io.imem.addr.poke(1.U)
      c.io.imem.inst.expect("h93A1B2C3".U)
    }
  }

  it should "M4 inhibit writes and preserve a neighboring word" in {
    simulate(new Memory(initFile)) { c =>
      disableWrite(c)
      // A=0，B=4。MEN_X 时即使跨过时钟边沿也不能修改 A。
      c.io.dmem.wdata.poke("hFFFFFFFF".U)
      c.clock.step(1)
      c.io.dmem.rdata.expect("h00500193".U)
      c.io.imem.addr.poke(4.U)
      c.io.imem.inst.expect("h00718193".U)

      // 写入 A 后，紧邻的 B 必须保持原值。
      c.io.dmem.wdata.poke("h12345678".U)
      c.io.dmem.wen.poke(MEN_S)
      c.clock.step(1)
      c.io.dmem.wen.poke(MEN_X)
      c.io.dmem.rdata.expect("h12345678".U)
      c.io.imem.inst.expect("h00718193".U)

      // 在写禁止状态下尝试覆盖 B，并回读 A，检查两处均未改变。
      c.io.dmem.addr.poke(4.U)
      c.io.dmem.wdata.poke(0.U)
      c.clock.step(1)
      c.io.dmem.rdata.expect("h00718193".U)
      c.io.imem.addr.poke(0.U)
      c.io.imem.inst.expect("h12345678".U)
    }
  }
}
