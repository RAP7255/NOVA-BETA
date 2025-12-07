package com.example.nova;

import androidx.lifecycle.ViewModel;
import com.example.nova.model.MeshMessage;
import java.util.ArrayList;

public class MessagesViewModel extends ViewModel {

    public final ArrayList<MeshMessage> messageList = new ArrayList<>();

}
