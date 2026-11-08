package com.hush.app.testing

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A [Clock] frozen at [millis] (UTC). */
fun fixedClock(millis: Long = 1_700_000_000_000L): Clock = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
