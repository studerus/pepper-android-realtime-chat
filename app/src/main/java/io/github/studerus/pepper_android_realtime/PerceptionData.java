package io.github.studerus.pepper_android_realtime;

/**
 * Data structures for perception information from Pepper's sensors
 */
public class PerceptionData {
    
    /**
     * Information about a detected human
     */
    public static class HumanInfo {
        public int id;
        public int estimatedAge = -1;
        public String gender = "Unknown";
        public String pleasureState = "Unknown";
        public String excitementState = "Unknown";
        public String engagementState = "Unknown";
        public String attentionState = "Unknown";
        public String smileState = "Unknown";
        public double distanceMeters = -1.0;
        public BasicEmotion basicEmotion = BasicEmotion.UNKNOWN;
        

        
        /**
         * Get attention level for UI display
         */
        public String getAttentionLevel() {
            if ("LOOKING_AT_ROBOT".equalsIgnoreCase(attentionState)) {
                return "Focused";
            } else if ("LOOKING_ELSEWHERE".equalsIgnoreCase(attentionState)) {
                return "Distracted";
            } else if ("LOOKING_UP".equalsIgnoreCase(attentionState)) {
                return "Looking Up";
            } else if ("LOOKING_DOWN".equalsIgnoreCase(attentionState)) {
                return "Looking Down";
            } else if ("LOOKING_LEFT".equalsIgnoreCase(attentionState)) {
                return "Looking Left";
            } else if ("LOOKING_RIGHT".equalsIgnoreCase(attentionState)) {
                return "Looking Right";
            } else if ("UNKNOWN".equalsIgnoreCase(attentionState)) {
                return "Unknown";
            }
            return capitalize(attentionState.replace("_", " "));
        }
        
        /**
         * Get engagement level for UI display
         */
        public String getEngagementLevel() {
            if ("INTERESTED".equalsIgnoreCase(engagementState)) {
                return "Engaged";
            } else if ("NOT_INTERESTED".equalsIgnoreCase(engagementState)) {
                return "Disengaged";
            } else if ("SEEKING_ENGAGEMENT".equalsIgnoreCase(engagementState)) {
                return "Seeking";
            } else if ("UNKNOWN".equalsIgnoreCase(engagementState)) {
                return "Unknown";
            }
            return capitalize(engagementState.replace("_", " "));
        }
        
        /**
         * Get formatted distance string
         */
        public String getDistanceString() {
            if (distanceMeters < 0) {
                return "Unknown";
            } else if (distanceMeters < 1.0) {
                return String.format(java.util.Locale.US, "%.0fcm", distanceMeters * 100);
            } else {
                return String.format(java.util.Locale.US, "%.1fm", distanceMeters);
            }
        }
        
        /**
         * Get age and gender summary
         */
        public String getDemographics() {
            StringBuilder demo = new StringBuilder();
            
            if (estimatedAge > 0) {
                demo.append(estimatedAge).append("y");
            }
            
            if (!"Unknown".equals(gender)) {
                if (demo.length() > 0) demo.append(", ");
                if ("MALE".equalsIgnoreCase(gender)) {
                    demo.append("♂️");
                } else if ("FEMALE".equalsIgnoreCase(gender)) {
                    demo.append("♀️");
                } else {
                    demo.append(capitalize(gender));
                }
            }
            
            return demo.length() > 0 ? demo.toString() : "Unknown";
        }
        
        /**
         * Compute basic emotion from excitement and pleasure states
         * Based on James Russell's emotion model as used in QiSDK
         */
        public static BasicEmotion computeBasicEmotion(String excitementState, String pleasureState) {
            if ("UNKNOWN".equalsIgnoreCase(excitementState) || "UNKNOWN".equalsIgnoreCase(pleasureState)) {
                return BasicEmotion.UNKNOWN;
            }
            
            if ("NEUTRAL".equalsIgnoreCase(pleasureState)) {
                return BasicEmotion.NEUTRAL;
            }
            
            if ("POSITIVE".equalsIgnoreCase(pleasureState)) {
                return "CALM".equalsIgnoreCase(excitementState) ? BasicEmotion.CONTENT : BasicEmotion.JOYFUL;
            }
            
            if ("NEGATIVE".equalsIgnoreCase(pleasureState)) {
                return "CALM".equalsIgnoreCase(excitementState) ? BasicEmotion.SAD : BasicEmotion.ANGRY;
            }
            
            return BasicEmotion.NEUTRAL;
        }
        
        /**
         * Get basic emotion for display
         */
        public String getBasicEmotionDisplay() {
            return basicEmotion.getFormattedDisplay();
        }
        
        /**
         * Capitalize first letter of each word
         */
        private String capitalize(String input) {
            if (input == null || input.isEmpty()) {
                return input;
            }
            
            String[] words = input.toLowerCase().split("\\s+");
            StringBuilder result = new StringBuilder();
            
            for (int i = 0; i < words.length; i++) {
                if (i > 0) result.append(" ");
                if (words[i].length() > 0) {
                    result.append(Character.toUpperCase(words[i].charAt(0)));
                    if (words[i].length() > 1) {
                        result.append(words[i].substring(1));
                    }
                }
            }
            
            return result.toString();
        }
    }
    
    // Snapshot and status classes removed for now; can be reintroduced when dashboard requires them
}
