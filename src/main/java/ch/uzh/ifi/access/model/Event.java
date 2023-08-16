package ch.uzh.ifi.access.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonGetter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Getter
@Setter
@Entity
public class Event {
    @Id
    @GeneratedValue
    public Long id;

    public String type;

    public String description;

    @JsonFormat(pattern = "yyyy-MM-dd")
    public LocalDateTime date;

    @ManyToOne
    @JoinColumn(name = "course_id")
    public Course course;

    @JsonGetter
    public String getTime() {
        return date.format(DateTimeFormatter.ofPattern("HH:mm"));
    }


}