package p2p.di

import org.koin.dsl.module
import p2p.helpers.RemotePeerReputationManager
import p2p.utils.Logger

/**
 * Main application module for dependency injection
 */
val appModule = module {
    single { Logger() }
    single { RemotePeerReputationManager(get()) }
}