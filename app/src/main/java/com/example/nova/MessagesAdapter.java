package com.example.nova;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.nova.model.MeshMessage;

import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<MeshMessage> messageList;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public MessagesAdapter(Context context, List<MeshMessage> messageList) {
        this.context = context;
        this.messageList = messageList;
        startAutoRemoveTask();
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        MeshMessage msg = messageList.get(position);
        holder.sender.setText(msg.sender);
        holder.content.setText(msg.payload);
        holder.date.setText(msg.timestamp);
    }

    @Override
    public int getItemCount() {
        return messageList.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView sender, content, date;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            sender = itemView.findViewById(R.id.senderText);
            content = itemView.findViewById(R.id.messageText);
            date = itemView.findViewById(R.id.dateText);
        }
    }

    // Auto-remove messages older than 1 hour
    private void startAutoRemoveTask() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                Iterator<MeshMessage> iterator = messageList.iterator();
                boolean removed = false;

                while (iterator.hasNext()) {
                    MeshMessage msg = iterator.next();
                    try {
                        long msgTime = new SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()
                        ).parse(msg.timestamp).getTime();

                        if (currentTime - msgTime > 3600 * 1000) { // > 1 hr
                            iterator.remove();
                            removed = true;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (removed) notifyDataSetChanged();
                handler.postDelayed(this, 60 * 1000);
            }
        }, 60 * 1000);
    }
}
