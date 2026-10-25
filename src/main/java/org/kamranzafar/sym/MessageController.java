package org.kamranzafar.sym;

import dev.halyx.error.CloudException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
class MessageController {

    record PostRequest(String text) {
    }

    record MessageView(String author, String messageId, String text, Instant postedAt) {

        static MessageView of(Message message) {
            return new MessageView(message.author(), message.messageId(), message.text(), message.postedAt());
        }

        static MessageView of(HashtagEntry entry) {
            return new MessageView(entry.author(), entry.messageId(), entry.text(), entry.postedAt());
        }
    }

    private final MessageService service;

    MessageController(MessageService service) {
        this.service = service;
    }

    @PostMapping("/users/{author}/messages")
    ResponseEntity<MessageView> post(@PathVariable String author, @RequestBody PostRequest request) {
        Message message = service.post(author, request.text());
        return ResponseEntity.created(URI.create("/users/" + author + "/messages/" + message.messageId()))
                .body(MessageView.of(message));
    }

    @GetMapping("/users/{author}/messages/{messageId}")
    ResponseEntity<MessageView> message(@PathVariable String author, @PathVariable String messageId) {
        return ResponseEntity.of(service.find(author, messageId).map(MessageView::of));
    }

    @GetMapping("/users/{author}/messages")
    List<MessageView> timeline(@PathVariable String author, @RequestParam(defaultValue = "20") int limit) {
        return service.timeline(author, limit).stream().map(MessageView::of).toList();
    }

    @GetMapping("/hashtags/{tag}/messages")
    List<MessageView> tagged(@PathVariable String tag, @RequestParam(defaultValue = "20") int limit) {
        return service.tagged(tag, limit).stream().map(MessageView::of).toList();
    }

    @ExceptionHandler(InvalidMessageException.class)
    ProblemDetail invalid(InvalidMessageException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** The document store or the stream failed; Halyx raises the same exceptions on every cloud. */
    @ExceptionHandler(CloudException.class)
    ProblemDetail unavailable(CloudException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "try again later");
    }
}
