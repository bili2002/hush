package com.hush.app.di

import javax.inject.Qualifier

/** Process-lifetime [kotlinx.coroutines.CoroutineScope] (SupervisorJob + IO). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
