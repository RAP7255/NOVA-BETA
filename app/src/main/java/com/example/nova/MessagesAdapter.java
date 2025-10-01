package com.example.nova;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.nova.model.MeshMessage;

import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<MeshMessage> messageList; // ✅ changed to MeshMessage
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Pass context + MeshMessage list
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
        MeshMessage msg = messageList.get(position); // ✅ MeshMessage
        holder.sender.setText(msg.sender);
        holder.date.setText(msg.timestamp);

        // Highlight location links in red
        Spannable spannable = new SpannableString(msg.payload);
        Pattern pattern = Pattern.compile("Lat: [-+]?\\d*\\.?\\d+, Lon: [-+]?\\d*\\.?\\d+");
        Matcher matcher = pattern.matcher(msg.payload);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            spannable.setSpan(
                    new ForegroundColorSpan(ContextCompat.getColor(context, android.R.color.holo_red_light)),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }
        holder.content.setText(spannable);
        Linkify.addLinks(holder.content, Linkify.WEB_URLS);

        // Open Maps on location tap
        holder.content.setOnClickListener(v -> {
            Matcher m = pattern.matcher(msg.payload);
            if (m.find()) {
                String loc = m.group();
                String[] parts = loc.replace("Lat: ", "").replace("Lon: ", "").split(",");
                if (parts.length == 2) {
                    String lat = parts[0].trim();
                    String lon = parts[1].trim();
                    String geoUri = "geo:" + lat + "," + lon + "?q=" + lat + "," + lon;
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
                    intent.setPackage("com.google.android.apps.maps");
                    try {
                        context.startActivity(intent);
                    } catch (Exception e) {
                        e.printStackTrace(); // no maps installed
                    }
                }
            }
        });
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
                Iterator<MeshMessage> iterator = messageList.iterator(); // ✅ MeshMessage
                boolean removed = false;

                while (iterator.hasNext()) {
                    MeshMessage msg = iterator.next();
                    try {
                        long msgTime = new SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault() // match MeshMessage timestamp format
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
                handler.postDelayed(this, 60 * 1000); // every 1 min
            }
        }, 60 * 1000);
    }
}
