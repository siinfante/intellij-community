// "Replace with 'newFun(p1, p2, 0)'" "true"

interface X {
    @deprecated("", ReplaceWith("newFun(p1, p2, 0)"))
    fun oldFun(p1: Int, p2: Int)

    fun newFun(p1: Int, p2: Int, p3: Int)
}

fun foo(x: X) {
    x.<caret>oldFun(
            1,
            2
    )
}
