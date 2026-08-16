package BMS.Try2;

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
import lombok.val;

enum SeatType {
    FRONT("FRONT", 300.99), MIDDLE("MIDDLE", 500.99), RECLINER("RECLINER", 1000.99);

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

    public boolean LockForUser(User u) {
        if (user.equals(u) && expiry.isAfter(Instant.now())) {
            return true;
        }
        try {
            if (lock.tryLock(1, TimeUnit.MINUTES)) {
                user = u;
                expiry = Instant.now();
                return true;
            }
        } catch (Exception e) {
            System.out.println("Lock not available for user");
        }
        return false;
    }

    void unlock() {
        lock.unlock();
        user = null;
        expiry = Instant.now();
    }

    boolean cleanIsExpired() {
        if (expiry.isBefore(Instant.now())) {
            lock.unlock();
            user = null;
            return false;
        }
        return true;
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
        List<SeatLock> aquiredLocks = new ArrayList<>();
        boolean getAllLocks = true;
        for (int id : seatIds) {
            SeatLock s = seatLockMap.get(id);
            if (s != null) {
                if (!s.LockForUser(u))
                    return false;
                aquiredLocks.add(seatLockMap.get(id));
            } else {
                getAllLocks = false;
            }
        }
        if (!getAllLocks) {
            for (var sl : aquiredLocks) {
                sl.unlock();
            }
            return false;
        }
        for (SeatLock s : aquiredLocks) {
            s.getSeat().setStatus(SeatStatus.HOLD);
        }
        System.out.println("Seat reserved for user");
        return true;
    }

    Double confirmSeats(User u, List<Integer> seatIds) {
        List<SeatLock> aquiredLocks = new ArrayList<>();
        Double totalAmount = -1.0;
        boolean getAllLocks = true;
        for (int id : seatIds) {
            if (seatLockMap.get(id).LockForUser(u)) {
                aquiredLocks.add(seatLockMap.get(id));
            } else {
                getAllLocks = false;
            }
        }
        if (!getAllLocks) {
            for (var sl : aquiredLocks) {
                sl.unlock();
            }
            return totalAmount;
        }
        for (SeatLock s : aquiredLocks) {
            s.getSeat().setStatus(SeatStatus.UNAVAILABLE);
        }
        totalAmount = aquiredLocks.stream().mapToDouble(l -> l.getSeat().getType().getValue()).sum();
        return totalAmount;
    }

    void clean() {
        for (var lock : seatLockMap.entrySet()) {
            lock.getValue().cleanIsExpired();
        }
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
        pm.pay(total);
        System.out.println("AMOUNT PAID:" + total + " Booking confirmed");
        return true;
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
        bm.reserveSeatsForUser(s, new ArrayList<>(List.of(1, 2)), u);
        bm.confirmSeatForUser(s, new ArrayList<>(List.of(1, 2)), u);
    }
}