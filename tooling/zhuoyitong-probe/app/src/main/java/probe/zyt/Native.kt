package probe.zyt

object Native {
    init { System.loadLibrary("zytprobe") }
    external fun init(progressPath: String)
    external fun suite(): String
    external fun socketBlocked(): Boolean
    external fun net(): String
    external fun sweep(elf: String, from: Int, to: Int): String
    external fun sweepFork(from: Int, to: Int): String
    external fun exec(key: String, path: String, args: Array<String>): String
}
