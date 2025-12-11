package ch.fhnw.pepper_realtime.di

import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager

/**
 * Groups all navigation-related dependencies.
 * Includes map management and location handling.
 */
data class NavigationDependencies(
    val navigationServiceManager: NavigationServiceManager,
    val locationProvider: LocationProvider
)

