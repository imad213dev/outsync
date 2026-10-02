package dev.outsync

public interface PayloadCodec<T : Any> {
    public fun encode(value: T): String
    public fun decode(encoded: String): T
}
