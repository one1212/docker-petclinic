package org.springframework.samples.petclinic.web;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 미용 예약 API (Java 21).
 * <ul>
 *   <li>GET  /api/grooming/bookings                      최근 예약 50건</li>
 *   <li>GET  /api/grooming/bookings/availability?date=   날짜별 시간대 현황 (시간당 정원 3명)</li>
 *   <li>GET  /api/grooming/bookings/calendar?month=      월별 예약 달력</li>
 *   <li>POST /api/grooming/bookings                      예약 저장 (정원 초과 시 409)</li>
 *   <li>POST /api/grooming/bookings/lookup               본인 예약 조회 (이름 + 연락처 둘 다 일치)</li>
 * </ul>
 * 목록 · 상세 · 달력은 관리자 전용이며, 접근 제어는 앞단 Apache(WEB)에서 함
 * (apache-backend/zz-petclinic.conf).
 */
@RestController
@RequestMapping("/api/grooming/bookings")
public class GroomingBookingController {

    /** 시간당 기본 정원 (grooming_slots.capacity 기본값과 동일) */
    static final int DEFAULT_CAPACITY = 3;

    /** 예약 가능한 시간대 (1시간 단위, 12시는 점심시간) */
    static final List<LocalTime> SLOT_TIMES = List.of(
        LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(13, 0), LocalTime.of(14, 0),
        LocalTime.of(15, 0), LocalTime.of(16, 0), LocalTime.of(17, 0));

    /** 취소된 예약은 정원 계산·달력에서 제외 */
    private static final String ACTIVE = "status <> 'CANCELLED'";

    /** 시간대 상태 */
    enum SlotStatus { AVAILABLE, FULL, CLOSED }

    /** 예약 요청 본문 (Jackson 2.12+ 는 record 를 바로 역직렬화) */
    public record GroomingBookingRequest(
        String guardianName, String phone, String petName, String petType, String petWeight,
        String service, String preferredDate, String preferredTime, String memo) {
    }

    /** 본인 예약 조회 요청 본문 (개인정보라 URL 쿼리가 아닌 POST 본문으로 받음) */
    public record LookupRequest(String guardianName, String phone) {
    }

    /** 시간대 현황 응답 한 칸 */
    public record Slot(String time, int booked, int capacity, int remaining, SlotStatus status) {
    }

    /** 트랜잭션 안에서 계산한 예약 결과 */
    private record BookingResult(boolean full, int booked, int capacity) {
        int remaining() {
            return Math.max(capacity - booked, 0);
        }
    }

    /** 예약 조회 시도 제한: 10분에 10번 */
    static final int LOOKUP_MAX_ATTEMPTS = 10;
    static final long LOOKUP_WINDOW_MS = 10 * 60 * 1000L;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;
    private final Map<String, Deque<Long>> lookupAttempts = new ConcurrentHashMap<>();

    @Autowired
    public GroomingBookingController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 기본 설정(JPA)과 별개로, 이 컨트롤러는 JDBC 트랜잭션을 직접 사용
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
    }

    /**
     * 최근 예약 50건. 관리자 전용 (Apache 에서 관리자 인증을 통과한 ADMIN_PATH/api/... 요청만 옴)
     * → 관리자가 연락할 수 있도록 이름을 가리지 않음.
     */
    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
            SELECT id, guardian_name AS guardianName, pet_name AS petName, service,
                   DATE_FORMAT(preferred_at, '%Y-%m-%d') AS preferredDate,
                   DATE_FORMAT(preferred_at, '%H:%i') AS preferredTime, status
            FROM grooming_bookings
            ORDER BY preferred_at DESC
            LIMIT 50""");
    }

    /**
     * 예약 상세 (달력에서 한 건을 눌렀을 때). 관리자 전용 → 이름 · 연락처 전체.
     * 예) GET /api/grooming/bookings/7
     */
    @GetMapping("/{id:\\d+}")
    public ResponseEntity<?> detail(@PathVariable("id") long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, guardian_name AS guardianName, phone, pet_name AS petName, pet_type AS petType,
                   pet_weight AS petWeight, service,
                   DATE_FORMAT(preferred_at, '%Y-%m-%d') AS date, DATE_FORMAT(preferred_at, '%H:%i') AS time,
                   memo, status, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS createdAt
            FROM grooming_bookings
            WHERE id = ?""", id);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(message("예약을 찾을 수 없습니다."));
        }
        return ResponseEntity.ok(rows.get(0));
    }

    /**
     * 날짜별 시간대 예약 현황.
     * 예) GET /api/grooming/bookings/availability?date=2026-11-11
     * → [{"time":"15:00","booked":2,"capacity":3,"remaining":1,"status":"AVAILABLE"}, ...]
     */
    @GetMapping("/availability")
    public ResponseEntity<?> availability(@RequestParam("date") String dateParam) {
        final LocalDate date;
        try {
            date = LocalDate.parse(dateParam.strip());
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body(message("날짜 형식을 확인해주세요. (예: 2026-11-11)"));
        }

        var dayStart = Timestamp.valueOf(date.atStartOfDay());
        var dayEnd = Timestamp.valueOf(date.plusDays(1).atStartOfDay());

        // 시간대별 예약 수
        var bookedByHour = new HashMap<Integer, Integer>();
        jdbcTemplate.query("""
            SELECT HOUR(preferred_at) AS h, COUNT(*) AS cnt
            FROM grooming_bookings
            WHERE preferred_at >= ? AND preferred_at < ? AND %s
            GROUP BY HOUR(preferred_at)""".formatted(ACTIVE),
            rs -> { bookedByHour.put(rs.getInt("h"), rs.getInt("cnt")); }, dayStart, dayEnd);

        // 시간대별 정원 (따로 지정된 슬롯만 DB에 있음, 없으면 기본 정원)
        var capacityByHour = new HashMap<Integer, Integer>();
        jdbcTemplate.query(
            "SELECT HOUR(slot_at) AS h, capacity FROM grooming_slots WHERE slot_at >= ? AND slot_at < ?",
            rs -> { capacityByHour.put(rs.getInt("h"), rs.getInt("capacity")); }, dayStart, dayEnd);

        var now = LocalDateTime.now();
        List<Slot> slots = SLOT_TIMES.stream().map(time -> {
            int booked = bookedByHour.getOrDefault(time.getHour(), 0);
            int capacity = capacityByHour.getOrDefault(time.getHour(), DEFAULT_CAPACITY);
            int remaining = Math.max(capacity - booked, 0);
            SlotStatus status;
            if (LocalDateTime.of(date, time).isBefore(now)) {
                status = SlotStatus.CLOSED;
            } else if (remaining == 0) {
                status = SlotStatus.FULL;
            } else {
                status = SlotStatus.AVAILABLE;
            }
            return new Slot(time.toString(), booked, capacity, remaining, status);
        }).toList();
        return ResponseEntity.ok(slots);
    }

    /**
     * 월별 예약 달력 (취소 제외, 날짜·시간 순).
     * 예) GET /api/grooming/bookings/calendar?month=2026-11
     */
    @GetMapping("/calendar")
    public ResponseEntity<?> calendar(@RequestParam("month") String monthParam) {
        final YearMonth month;
        try {
            month = YearMonth.parse(monthParam.strip());
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body(message("월 형식을 확인해주세요. (예: 2026-11)"));
        }
        var from = Timestamp.valueOf(month.atDay(1).atStartOfDay());
        var to = Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay());
        return ResponseEntity.ok(jdbcTemplate.queryForList("""
            SELECT id, DATE_FORMAT(preferred_at, '%%Y-%%m-%%d') AS date, DATE_FORMAT(preferred_at, '%%H:%%i') AS time,
                   guardian_name AS guardianName, pet_name AS petName, pet_type AS petType, service, status
            FROM grooming_bookings
            WHERE preferred_at >= ? AND preferred_at < ? AND %s
            ORDER BY preferred_at, id""".formatted(ACTIVE), from, to));
    }

    /**
     * 본인 예약 조회 (로그인 없는 사용자용).
     * 이름과 연락처가 둘 다 일치하는 예약만 돌려줌. 연락처는 숫자만 비교 (010-1234-5678 = 01012345678).
     * 응답에는 연락처 · 요청사항을 넣지 않음. 같은 접속지에서 10분에 10번까지 (넘으면 429).
     * guardian_name 인덱스로 후보를 먼저 좁힌 뒤 그 행들만 연락처 숫자를 비교함.
     * 예) POST /api/grooming/bookings/lookup  {"guardianName":"홍길동","phone":"010-1234-5678"}
     */
    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestBody(required = false) LookupRequest request, HttpServletRequest http) {
        long retryAfter = lookupRetryAfter(clientKey(http));
        if (retryAfter > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfter))
                .body(message("조회를 너무 많이 시도했어요. " + ((retryAfter + 59) / 60) + "분 후에 다시 시도해주세요."));
        }
        if (request == null || blank(request.guardianName()) || blank(request.phone())) {
            return ResponseEntity.badRequest().body(message("이름과 연락처를 모두 입력해주세요."));
        }
        if (tooLong(request.guardianName(), 60) || tooLong(request.phone(), 30)) {
            return ResponseEntity.badRequest().body(message("입력한 내용이 허용 길이를 초과했습니다."));
        }
        String digits = request.phone().replaceAll("[^0-9]", "");
        if (digits.length() < 9) {
            return ResponseEntity.badRequest().body(message("연락처를 정확히 입력해주세요."));
        }
        return ResponseEntity.ok(jdbcTemplate.queryForList("""
            SELECT DATE_FORMAT(preferred_at, '%Y-%m-%d') AS date, DATE_FORMAT(preferred_at, '%H:%i') AS time,
                   pet_name AS petName, pet_type AS petType, service, status,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i') AS createdAt
            FROM grooming_bookings
            WHERE guardian_name = ?
              AND REGEXP_REPLACE(phone, '[^0-9]', '') = ?
            ORDER BY preferred_at DESC
            LIMIT 20""", request.guardianName().strip(), digits));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody GroomingBookingRequest request) {
        if (request == null || blank(request.guardianName()) || blank(request.phone()) || blank(request.petName())
                || blank(request.petType()) || blank(request.service()) || blank(request.preferredDate())
                || blank(request.preferredTime())) {
            return ResponseEntity.badRequest().body(message("필수 항목을 모두 입력해주세요."));
        }
        if (tooLong(request.guardianName(), 60) || tooLong(request.phone(), 30) || tooLong(request.petName(), 60)
                || tooLong(request.petType(), 30) || tooLong(request.petWeight(), 30)
                || tooLong(request.service(), 50) || tooLong(request.memo(), 500)) {
            return ResponseEntity.badRequest().body(message("입력한 내용이 허용 길이를 초과했습니다."));
        }

        final LocalDateTime slotAt;
        try {
            slotAt = LocalDateTime.of(LocalDate.parse(request.preferredDate().strip()),
                                      LocalTime.parse(request.preferredTime().strip()));
        } catch (DateTimeParseException ex) {
            return ResponseEntity.badRequest().body(message("희망 날짜와 시간을 확인해주세요."));
        }
        if (!SLOT_TIMES.contains(slotAt.toLocalTime())) {
            return ResponseEntity.badRequest().body(message("예약 가능한 시간대를 선택해주세요."));
        }
        if (slotAt.isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(message("이미 지난 시간은 예약할 수 없습니다."));
        }


        var slotTs = Timestamp.valueOf(slotAt);
        var slotEnd = Timestamp.valueOf(slotAt.plusHours(1));

        // 1) 슬롯 행이 없으면 기본 정원으로 생성.
        //    트랜잭션 밖에서 바로 커밋해야 함 — 트랜잭션 안에서 INSERT 와 FOR UPDATE 를 같이 하면
        //    동시 요청끼리 잠금이 엇갈려 InnoDB 교착상태(deadlock)가 생김.
        jdbcTemplate.update("INSERT IGNORE INTO grooming_slots (slot_at, capacity) VALUES (?, ?)",
            slotTs, DEFAULT_CAPACITY);

        BookingResult result = reserveWithRetry(request, slotTs, slotEnd);

        var body = new LinkedHashMap<String, Object>();
        body.put("booked", result.booked());
        body.put("capacity", result.capacity());
        body.put("remaining", result.remaining());
        if (result.full()) {
            body.put("message", "선택한 시간대(%s)는 예약이 마감됐습니다. 다른 시간을 선택해주세요.".formatted(slotAt.toLocalTime()));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        body.put("status", "PENDING");
        body.put("message", "예약이 접수됐습니다.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** 잠금 충돌(교착상태·잠금 대기 초과)이 나면 최대 3번까지 다시 시도 */
    private BookingResult reserveWithRetry(GroomingBookingRequest request, Timestamp slotTs, Timestamp slotEnd) {
        for (int attempt = 1; ; attempt++) {
            try {
                return reserve(request, slotTs, slotEnd);
            } catch (PessimisticLockingFailureException ex) {
                if (attempt >= 3) {
                    throw ex;
                }
            }
        }
    }

    /** 한 트랜잭션 안에서: 슬롯 행 잠금 → 현재 예약 수 확인 → 정원 미만이면 저장 */
    private BookingResult reserve(GroomingBookingRequest request, Timestamp slotTs, Timestamp slotEnd) {
        return tx.execute(status -> {
            // 2) 슬롯 행 잠금 (같은 시간대 동시 예약은 여기서 줄을 섬)
            int capacity = jdbcTemplate.queryForObject(
                "SELECT capacity FROM grooming_slots WHERE slot_at = ? FOR UPDATE", Integer.class, slotTs);
            // 3) 현재 예약 수
            int booked = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM grooming_bookings WHERE preferred_at >= ? AND preferred_at < ? AND " + ACTIVE,
                Integer.class, slotTs, slotEnd);
            if (booked >= capacity) {
                return new BookingResult(true, booked, capacity);
            }
            // 4) 저장
            jdbcTemplate.update("""
                INSERT INTO grooming_bookings
                  (guardian_name, phone, pet_name, pet_type, pet_weight, service, preferred_at, memo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                request.guardianName().strip(), request.phone().strip(), request.petName().strip(),
                request.petType().strip(), emptyToNull(request.petWeight()), request.service().strip(),
                slotTs, emptyToNull(request.memo()));
            return new BookingResult(false, booked + 1, capacity);
        });
    }

    // ===== 예약 조회 시도 횟수 제한 =====

    /**
     * 같은 접속지(IP)에서 10분에 10번까지만 조회 허용 → 이름 · 번호를 바꿔 가며 남의 예약을 찾는 것을 막음.
     * 주의: WAS 인스턴스마다 따로 셈 (Pod 가 2개면 최대 2배). 더 엄격히 하려면 DB · Redis 같은 공용 저장소 필요.
     * @return 허용이면 0, 막히면 다시 시도할 수 있을 때까지 남은 초
     */
    private long lookupRetryAfter(String clientKey) {
        long now = System.currentTimeMillis();
        long windowStart = now - LOOKUP_WINDOW_MS;
        if (lookupAttempts.size() > 10_000) {   // 오래된 기록 정리 (메모리 보호)
            lookupAttempts.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast() < windowStart;
                }
            });
        }
        Deque<Long> times = lookupAttempts.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < windowStart) {
                times.pollFirst();
            }
            if (times.size() >= LOOKUP_MAX_ATTEMPTS) {
                return Math.max(1, (times.peekFirst() + LOOKUP_WINDOW_MS - now) / 1000);
            }
            times.addLast(now);
            return 0;
        }
    }

    /**
     * 접속지 IP. Apache(WEB) 가 X-Forwarded-For 맨 뒤에 실제 접속 주소를 붙임.
     * 맨 앞 값은 사용자가 마음대로 넣을 수 있으므로 쓰지 않고 맨 뒤 값을 씀.
     * (④ EKS 에서 ALB 뒤에 두면 맨 뒤 값이 ALB 주소가 되므로 그때는 ALB 가 붙인 값을 쓰도록 바꿔야 함)
     */
    private static String clientKey(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            return hops[hops.length - 1].strip();
        }
        return http.getRemoteAddr();
    }


    private static Map<String, Object> message(String text) {
        return Map.of("message", text);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value.strip();
    }
}
