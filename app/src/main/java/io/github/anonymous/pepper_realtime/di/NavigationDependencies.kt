package io.github.anonymous.pepper_realtime.di

import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager

/**
 * Groups all navigation-related dependencies.
 * Includes map management and location handling.
 */
data class NavigationDependencies(
    val navigationServiceManager: NavigationServiceManager,
    val locationProvider: LocationProvider
)

