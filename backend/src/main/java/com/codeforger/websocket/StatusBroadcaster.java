package com.codeforger.websocket;

import com.codeforger.dto.StatusBroadcast;
import com.codeforger.model.JobStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class StatusBroadcaster {

    private static final String TOPIC_PREFIX = "/topic/jobs/";

    private final SimpMessagingTemplate messagingTemplate;

    public StatusBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcast(String jobId, JobStatus phase, String message) {
        StatusBroadcast payload = StatusBroadcast.now(jobId, phase, message);
        messagingTemplate.convertAndSend(TOPIC_PREFIX + jobId, payload);
    }
}
