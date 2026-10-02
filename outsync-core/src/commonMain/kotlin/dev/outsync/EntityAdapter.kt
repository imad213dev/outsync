package dev.outsync

public interface EntityAdapter<T : Any> {
    public fun idOf(entity: T): String

    public fun versionOf(entity: T): Long?

    public fun withVersion(entity: T, version: Long): T
}
