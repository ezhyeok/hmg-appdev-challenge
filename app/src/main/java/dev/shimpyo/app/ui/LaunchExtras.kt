package dev.shimpyo.app.ui

/** RED 단계 자리 표시: 받은 것을 그대로 넘긴다 (지금 MainActivity의 동작) */
object LaunchExtras {
    const val DEMO = "demo"
    val DEV_FLAGS = listOf("selftest", "route", "verify")

    data class Forwarded(val demo: String?, val devFlags: List<String>) {
        val isEmpty: Boolean get() = demo == null && devFlags.isEmpty()
    }

    fun pick(demo: String?, flag: (String) -> Boolean, debugBuild: Boolean): Forwarded =
        Forwarded(demo, DEV_FLAGS.filter(flag))
}
