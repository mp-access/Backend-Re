package ch.uzh.ifi.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

@RestController
public class ServerInfoController {

    @GetMapping("/info")
    public Map<String, String> getAppInfo(@Value("${access.version}") String version) {
        return Map.of("version", version,
                "offsetDateTime", ZonedDateTime.now().toOffsetDateTime().toString(),
                "utcTime", Instant.now().toString(), "zoneId", ZoneId.systemDefault().toString());
    }
}
