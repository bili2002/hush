package com.hush.server.db

import kotlin.test.Test
import kotlin.test.assertEquals

class FtsPhraseTest {
    @Test
    fun `wraps the whole query in one phrase`() {
        assertEquals("\"hello world\"", ArchiveRepository.ftsPhrase("hello world"))
    }

    @Test
    fun `doubles inner double quotes`() {
        assertEquals("\"say \"\"hi\"\" now\"", ArchiveRepository.ftsPhrase("say \"hi\" now"))
    }

    @Test
    fun `leaves operators as literal text inside the phrase`() {
        assertEquals("\"-foo OR bar:baz*\"", ArchiveRepository.ftsPhrase("-foo OR bar:baz*"))
    }
}
