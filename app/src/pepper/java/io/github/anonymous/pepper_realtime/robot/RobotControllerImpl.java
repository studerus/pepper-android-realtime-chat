package io.github.anonymous.pepper_realtime.robot;

import com.aldebaran.qi.sdk.QiContext;

/**
 * Pepper-specific implementation of RobotController.
 * Provides access to real QiContext for full robot functionality.
 */
public class RobotControllerImpl implements RobotController {
    
    private QiContext qiContext;
    
    /**
     * Set the QiContext when robot focus is gained
     */
    public void setQiContext(QiContext qiContext) {
        this.qiContext = qiContext;
    }
    
    @Override
    public Object getRobotContext() {
        return qiContext;
    }
    
    @Override
    public boolean isRobotHardwareAvailable() {
        return true;  // Running on Pepper hardware
    }
    
    @Override
    public boolean isReady() {
        return qiContext != null;
    }
    
    @Override
    public String getModeName() {
        return "Pepper Robot Mode";
    }
}


