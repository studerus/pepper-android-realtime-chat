package io.github.hrilab.pepper_realtime;

public class ChatMessage {
    public enum Sender {
        USER, ROBOT
    }
    
    public enum Type {
        REGULAR_MESSAGE,
        FUNCTION_CALL,
        IMAGE_MESSAGE
    }

    private String message;
    private final Sender sender;
    private final Type type;
    private String imagePath; // optional
    private String itemId; // optional, for tracking Realtime API conversation items
    
    // Function call specific fields
    private String functionName;
    private String functionArgs;
    private String functionResult;
    private boolean isExpanded = false;

    // Private constructor 
    private ChatMessage(Sender sender, Type type) {
        this.sender = sender;
        this.type = type;
    }

    // Static factory for function call messages
    
    public static ChatMessage createFunctionCall(String functionName, String functionArgs, Sender sender) {
        ChatMessage chatMessage = new ChatMessage(sender, Type.FUNCTION_CALL);
        chatMessage.functionName = functionName;
        chatMessage.functionArgs = functionArgs;
        chatMessage.message = ""; // Will be generated in display
        return chatMessage;
    }

    // Keep original constructor for backward compatibility
    public ChatMessage(String message, Sender sender) {
        this(sender, Type.REGULAR_MESSAGE);
        this.message = message;
    }

    // Keep original image constructor for backward compatibility
    public ChatMessage(String message, String imagePath, Sender sender) {
        this(sender, Type.IMAGE_MESSAGE);
        this.message = message;
        this.imagePath = imagePath;
    }

    // Getters and setters
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Sender getSender() { return sender; }
    public Type getType() { return type; }

    public String getImagePath() { return imagePath; }
    
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    
    public String getFunctionName() { return functionName; }
    public String getFunctionArgs() { return functionArgs; }
    public String getFunctionResult() { return functionResult; }
    public void setFunctionResult(String functionResult) { this.functionResult = functionResult; }
    
    public boolean isExpanded() { return isExpanded; }
    public void setExpanded(boolean expanded) { this.isExpanded = expanded; }
}

