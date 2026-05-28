package com.codeforger.queue;

import com.codeforger.model.GenerationJob;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;

@Component
public class JobQueue {

    private final ConcurrentLinkedQueue<GenerationJob> pending = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, GenerationJob> all = new ConcurrentHashMap<>();

    public GenerationJob submit(String specUrl) {
        GenerationJob job = new GenerationJob(specUrl);
        all.put(job.getId(), job);
        pending.offer(job);
        return job;
    }

    public Optional<GenerationJob> find(String id) {
        return Optional.ofNullable(all.get(id));
    }

    public GenerationJob poll() {
        return pending.poll();
    }
}
