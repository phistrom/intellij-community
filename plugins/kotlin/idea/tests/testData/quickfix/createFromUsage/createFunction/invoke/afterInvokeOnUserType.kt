// "Create function 'invoke'" "true"

class A<T>(val n: T) {
    fun invoke(t: T, s: String): B<String> {
        throw UnsupportedOperationException("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class B<T>(val m: T)

fun test(): B<String> {
    return A(1)(2, "2")
}