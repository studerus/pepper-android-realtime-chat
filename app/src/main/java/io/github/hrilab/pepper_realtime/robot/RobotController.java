package io.github.hrilab.pepper_realtime.robot;

/**
 * Abstraction layer providing access to robot/simulation context.
 * Allows the app to run on Pepper robots (full functionality) or standalone devices (simulated).
 * 
 * Two implementations:
 * - PepperRobotController: Returns real QiContext for Pepper robot
 * - StubRobotController: Returns null (services will handle gracefully)
 */
public interface RobotController {
    
    /**
     * Get the robot context (QiContext for Pepper, null for Standalone)
     * Services must handle null gracefully by providing stub behavior
     */
    Object getRobotContext();
    
    /**
     * Check if robot hardware is available (true for Pepper, false for Standalone)
     */
    boolean isRobotHardwareAvailable();
    
    /**
     * Check if robot context is ready for operations
     */
    boolean isReady();
    
    /**
     * Get human-readable mode description for UI/logging
     */
    String getModeName();
}

