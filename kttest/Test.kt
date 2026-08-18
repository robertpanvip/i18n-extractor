fun generateKey(value: String): String {
    return value.trim()
        .replace(".", " ")
        .replace("@", "MARKER")
        .replace("|", " ")
        .trim()
}

fun main() {
    println("Hello world. -> [" + generateKey("Hello world.") + "]")
    println("Q&A -> [" + generateKey("Q&A") + "]")
    println("A|B -> [" + generateKey("A|B") + "]")
    println("3.14 -> [" + generateKey("请输入 3.14 以上的值") + "]")
    println("save -> [" + generateKey("保存") + "]")
}