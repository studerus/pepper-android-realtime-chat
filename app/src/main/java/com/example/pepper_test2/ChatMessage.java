package com.example.pepper_test2;

public class ChatMessage {
    public enum Sender {
        USER, ROBOT
    }

    private String message;
    private final Sender sender;
    private String imagePath; // optional

    public ChatMessage(String message, Sender sender) {
        this.message = message;
        this.sender = sender;
    }

    public ChatMessage(String message, String imagePath, Sender sender) {
        this.message = message;
        this.imagePath = imagePath;
        this.sender = sender;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Sender getSender() { return sender; }

    public String getImagePath() { return imagePath; }
}

