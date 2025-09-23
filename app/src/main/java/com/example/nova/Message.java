package com.example.nova;

public class Message {
    private String sender;
    private String content;
    private String date;

    public Message(String sender, String content, String date) {
        this.sender = sender;
        this.content = content;
        this.date = date;
    }

    public String getSender() { return sender; }
    public String getContent() { return content; }
    public String getDate() { return date; }
}
