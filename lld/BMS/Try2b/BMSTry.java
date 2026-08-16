import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.val;

enum SeatType {
    FRONT("FRONT", 300.99),
    MIDDLE("MIDDLE", 500.99),
    RECLINER("RECLINER", 1000.99);

    SeatType(String s, Double amt) {
        key = s;
        value = amt;
    }

    private final String key;
    private final Double value;

    String getKey() {
        return key;
    }

    Double getValue() {
        return value;
    }
}

enum SeatStatus {
    AVAILABLE, UNAVAILABLE, HOLD;
}

enum ShowType {
    MOVIES, STANDUP, CONCERT;
}

@AllArgsConstructor
@Data
class Seat {
    int seatId;
    SeatType type;
    SeatStatus status;
}

@AllArgsConstructor
@Data
class Auditorium {
    int audiId;
    String name;
    List<Seat> seats;
}

@AllArgsConstructor
@Data
class Theatre {
    int theaterId;
    String name;
    String city;
    List<Auditorium> auditoriums;
}

@Data
class SeatLock {
    Seat seat;
    Lock lock;
    Instant expiry;
    User user;

    SeatLock(Seat s) {
        seat = s;
        lock = new ReentrantLock();
        expiry = Instant.now();
    }

    public boolean lockForUser(User u) {
        if (seat.getStatus() == SeatStatus.UNAVAILABLE) {
            return false;
        }
        // Check if already locked by THIS user
        if (user != null && user.equals(u) && expiry.isAfter(Instant.now())) {
            return true;
        }

        // Check if locked by DIFFERENT user
        if (user != null && !user.equals(u) && expiry.isAfter(Instant.now())) {
            return false; // ✓ Explicitly reject different user
        }

        try {
            if (lock.tryLock(1, TimeUnit.MINUTES)) {
                user = u;
                expiry = Instant.now().plus(15, ChronoUnit.MINUTES);
                return true;
            }
        } catch (Exception e) {
            System.out.println("Lock not available for user");
        }
        return false;
    }

    void unlock() {
        try {
            lock.unlock(); // Unlock if held
            user = null;
            expiry = Instant.now();
            seat.setStatus(SeatStatus.AVAILABLE);
        } catch (IllegalMonitorStateException e) {
            // Lock wasn't held by current thread
        }
    }

    boolean cleanIsExpired() {
        if (lock.tryLock()) {
            try {
                if (expiry.isBefore(Instant.now())) {
                    user = null;
                    seat.setStatus(SeatStatus.AVAILABLE);
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }
        return false;
    }
}

class SeatManager {
    int id;
    List<Seat> seats;
    Map<Integer, SeatLock> seatLockMap;
    ScheduledExecutorService cleaner;

    SeatManager(List<Seat> seats) {
        seatLockMap = new ConcurrentHashMap<>();
        for (Seat s : seats) {
            seatLockMap.put(s.getSeatId(), new SeatLock(s));
        }
        cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> clean(), 0, 1, TimeUnit.MINUTES);
    }

    boolean reserveSeats(User u, List<Integer> seatIds) {
        List<SeatLock> acquiredLocks = new ArrayList<>();
        boolean getAllLocks = true;
        for (int id : seatIds) {
            SeatLock s = seatLockMap.get(id);
            if (s != null) {
                if (!s.lockForUser(u)) {
                    getAllLocks = false;
                    break;
                }
                acquiredLocks.add(seatLockMap.get(id));
            } else {
                getAllLocks = false;
            }
        }
        if (!getAllLocks) {
            for (var sl : acquiredLocks) {
                sl.unlock();
            }
            System.out.println("Seats: " + seatIds.toString() + " cant be reserved for user " + u);
            return false;
        }
        for (SeatLock s : acquiredLocks) {
            s.getSeat().setStatus(SeatStatus.HOLD);
        }
        System.out.println("Seats: " + seatIds.toString() + " reserved for user " + u);
        return true;
    }

    Double confirmSeats(User u, List<Integer> seatIds) {
        List<SeatLock> acquiredLocks = new ArrayList<>();
        Double totalAmount = -1.0;
        boolean getAllLocks = true;
        for (int id : seatIds) {
            SeatLock seatLock = seatLockMap.get(id);
            if (seatLock != null && seatLock.lockForUser(u)) {
                acquiredLocks.add(seatLock);
            } else {
                getAllLocks = false;
            }
        }
        if (!getAllLocks) {
            for (var sl : acquiredLocks) {
                sl.unlock();
            }
            return totalAmount;
        }
        for (SeatLock s : acquiredLocks) {
            s.getSeat().setStatus(SeatStatus.UNAVAILABLE);
            s.lock.unlock();
        }
        totalAmount = acquiredLocks.stream().mapToDouble(l -> l.getSeat().getType().getValue()).sum();
        return totalAmount;
    }

    void shutdown() {
        cleaner.shutdownNow();
    }

    void clean() {
        List<Integer> toRemove = new ArrayList<>();
        for (var lock : seatLockMap.entrySet()) {
            if (lock.getValue().getSeat().getStatus() == SeatStatus.UNAVAILABLE) {
                toRemove.add(lock.getKey());
            } else {
                lock.getValue().cleanIsExpired();
            }
        }
        toRemove.forEach(seatLockMap::remove);
    }
}

@AllArgsConstructor
@Data
class Show {
    int id;
    ShowType type;
    SeatManager sm;
    Theatre theatre;

    boolean reserveSeatsForUser(User u, List<Integer> seatIds) {
        return sm.reserveSeats(u, seatIds);
    }
}

@AllArgsConstructor
@ToString
@EqualsAndHashCode
class User {
    int id;
    String name;
    String email;
}

class PaymentManager {
    boolean pay(Double amount) {
        return true;
    }

}

@AllArgsConstructor
@Data
class BookingManager {
    PaymentManager pm;
    List<Show> shows;

    boolean reserveSeatsForUser(Show s, List<Integer> seatIds, User u) {
        if (shows.indexOf(s) == -1) {
            return false;// no show not active show
        }
        return s.reserveSeatsForUser(u, seatIds);
    }

    boolean confirmSeatForUser(Show s, List<Integer> seatIds, User u) {
        Double total = s.getSm().confirmSeats(u, seatIds);
        if (total < 0)
            return false;
        pm.pay(total);
        System.out.println(
                "Seats:" + seatIds.toString() + " confirmed AMOUNT PAID:" + total + " Booking confirmed for: " + u);
        return true;
    }

    void shutdown() {
        shows.forEach(s -> s.getSm().shutdown());
    }
}

public class BMSTry {

    public static void main(String[] args) {
        List<Seat> allSeat = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            SeatType s;
            if (i % 3 == 0) {
                s = SeatType.FRONT;
            } else if (i % 3 == 1) {
                s = SeatType.MIDDLE;
            } else {
                s = SeatType.RECLINER;
            }
            allSeat.add(new Seat(i, s, SeatStatus.AVAILABLE));
        }
        Auditorium a = new Auditorium(0, "AUDI1", allSeat);
        Theatre th = new Theatre(0, "PVR", "BLR", new ArrayList<>(List.of(a)));
        Show s = new Show(0, ShowType.MOVIES, new SeatManager(allSeat), th);

        BookingManager bm = new BookingManager(new PaymentManager(), new ArrayList<>(List.of(s)));

        User u = new User(0, "lasd", "asds.dsf@adsa.com");
        User u2 = new User(1, "lasasdd", "asdsasdas.dsf@adsa.com");

        bm.reserveSeatsForUser(s, new ArrayList<>(List.of(1, 2)), u);

        bm.reserveSeatsForUser(s, new ArrayList<>(List.of(1, 2, 3)), u2);

        bm.confirmSeatForUser(s, new ArrayList<>(List.of(1, 2)), u);
        bm.reserveSeatsForUser(s, new ArrayList<>(List.of(1, 2)), u);
        bm.confirmSeatForUser(s, new ArrayList<>(List.of(3)), u);

        bm.shutdown();
    }
}