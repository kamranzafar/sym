package org.kamranzafar.sym;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.halyx.error.CloudException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
class MessageController {

    record PostRequest(String text) {
    }

    /**
     * A message as the API shows it. {@code image} is the path of its image, left out when it has none. Hashtag
     * results never have it: the index is written when a message is posted, before any image is attached.
     */
    record MessageView(String author, String messageId, String text, Instant postedAt,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String image) {

        static MessageView of(Message message) {
            String image = message.imageType() == null ? null : path(message.author(), message.messageId()) + "/image";
            return new MessageView(message.author(), message.messageId(), message.text(), message.postedAt(), image);
        }

        static MessageView of(HashtagEntry entry) {
            return new MessageView(entry.author(), entry.messageId(), entry.text(), entry.postedAt(), null);
        }
    }

    private final MessageService service;

    MessageController(MessageService service) {
        this.service = service;
    }

    @PostMapping("/users/{author}/messages")
    ResponseEntity<MessageView> post(@PathVariable String author, @RequestBody PostRequest request) {
        Message message = service.post(author, request.text());
        return ResponseEntity.created(URI.create(path(author, message.messageId()))).body(MessageView.of(message));
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

    /** The body is the image itself, with its type as the Content-Type. One byte past the limit is enough to refuse it. */
    @PutMapping("/users/{author}/messages/{messageId}/image")
    ResponseEntity<MessageView> attachImage(@PathVariable String author, @PathVariable String messageId,
                                            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false)
                                            String contentType, InputStream body) throws IOException {
        byte[] image = body.readNBytes(MessageService.MAX_IMAGE_BYTES + 1);
        return ResponseEntity.of(service.attachImage(author, messageId, contentType, image).map(MessageView::of));
    }

    @GetMapping("/users/{author}/messages/{messageId}/image")
    ResponseEntity<byte[]> image(@PathVariable String author, @PathVariable String messageId) {
        // nosniff: browsers take the stored type as given, which is always one of the image types sym checked
        return service.image(author, messageId)
                .map(image -> ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                        .header("X-Content-Type-Options", "nosniff").body(image.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/users/{author}/messages/{messageId}/image")
    ResponseEntity<Void> removeImage(@PathVariable String author, @PathVariable String messageId) {
        return service.removeImage(author, messageId).isPresent()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(InvalidMessageException.class)
    ProblemDetail invalid(InvalidMessageException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InvalidImageException.class)
    ProblemDetail invalidImage(InvalidImageException e) {
        HttpStatus status = switch (e.reason()) {
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case UNSUPPORTED_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case EMPTY, NOT_THAT_TYPE -> HttpStatus.BAD_REQUEST;
        };
        return ProblemDetail.forStatusAndDetail(status, e.getMessage());
    }

    /**
     * The document store or the stream failed; Halyx raises the same exceptions on every cloud. Only a retryable
     * failure (throttled or unavailable) asks the client to try again; anything else is a fault on our side.
     */
    @ExceptionHandler(CloudException.class)
    ProblemDetail cloudFailure(CloudException e) {
        return e.isRetryable()
                ? ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "try again later")
                : ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static String path(String author, String messageId) {
        return "/users/" + author + "/messages/" + messageId;
    }
}
