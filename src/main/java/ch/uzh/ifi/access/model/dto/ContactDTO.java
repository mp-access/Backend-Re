package ch.uzh.ifi.access.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContactDTO {
    String name;
    String email;
    String message;
    String topic;

    public String formatContent() {
        return "Name: %s%nEmail: %s%nTopic: %s%nMessage: %s".formatted(name, email, topic, message);
    }
}
