package au.org.resourcework.waste_tracking_api.records;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PageResult<T>(List<T> items, int page, int size, long totalItems, long totalPages) {
    public static void validate(int page, int size) {
        if (page < 0 || page > 1_000_000 || !List.of(10,25,50).contains(size))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a non-negative page (maximum 1,000,000) and size 10, 25 or 50.");
    }
    public static int lastPage(long total, int size) { return (int)Math.max(0,(total-1)/size); }
}
