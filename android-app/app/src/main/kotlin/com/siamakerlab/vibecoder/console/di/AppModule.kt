package com.siamakerlab.vibecoder.console.di

import android.content.Context
import com.siamakerlab.vibecoder.console.data.local.AppPreferences
import com.siamakerlab.vibecoder.console.data.remote.KtorClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun providePrefs(@ApplicationContext ctx: Context): AppPreferences = AppPreferences(ctx)

    @Provides @Singleton
    fun provideKtorFactory(prefs: AppPreferences): KtorClientFactory = KtorClientFactory(prefs)

    @Provides @Singleton
    fun provideHttpClient(factory: KtorClientFactory): HttpClient = factory.create()
}
