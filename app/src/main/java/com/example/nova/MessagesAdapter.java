package com.example.nova;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
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

import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessagesAdapter extends RecyclerView.Adapter<MessagesAdapter.MessageViewHolder> {

    private final List<Message> messageList;
    private final Context context;
    private final Handler handler = new Handler();

    // ✅ Pass context properly
    public MessagesAdapter(Context context, List<Message> messageList) {
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
        Message msg = messageList.get(position);
        holder.sender.setText(msg.getSender());
        holder.date.setText(msg.getDate());

        // Highlight location links in red and make clickable
        Spannable spannable = new SpannableString(msg.getContent());
        Pattern pattern = Pattern.compile("Lat: [-+]?\\d*\\.?\\d+, Lon: [-+]?\\d*\\.?\\d+");
        Matcher matcher = pattern.matcher(msg.getContent());
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

        // Click to open location in Google Maps if present
        holder.content.setOnClickListener(v -> {
            Matcher m = pattern.matcher(msg.getContent());
            if (m.find()) {
                String loc = m.group();
                String[] parts = loc.replace("Lat: ", "").replace("Lon: ", "").split(",");
                if (parts.length == 2) {
                    String lat = parts[0].trim();
                    String lon = parts[1].trim();
                    String geoUri = "geo:" + lat + "," + lon + "?q=" + lat + "," + lon;
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
                    intent.setPackage("com.google.android.apps.maps");
                    context.startActivity(intent);
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

    // ✅ Auto-remove messages after 1 hour
    private void startAutoRemoveTask() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                Iterator<Message> iterator = messageList.iterator();
                boolean removed = false;

                while (iterator.hasNext()) {
                    Message msg = iterator.next();
                    try {
                        long msgTime = new SimpleDateFormat(
                                "EEE, dd MMM yyyy HH:mm:ss", Locale.getDefault()
                        ).parse(msg.getDate()).getTime();

                        if (currentTime - msgTime > 3600 * 1000) { // older than 1 hour
                            iterator.remove();
                            removed = true;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (removed) notifyDataSetChanged();
                handler.postDelayed(this, 60 * 1000); // check every 1 min
            }
        }, 60 * 1000);
    }
}
