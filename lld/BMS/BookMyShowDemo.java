// Complete, self-contained Java code (no Lombok) demonstrating core logic.

package BMS;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/* --- Enums & interfaces --- */
enum SeatStatus {
    AVAILABLE, HOLD, BOOKED
}

interface PaymentStrategy {
    boolean pay(int amount);
}

/* --- Payment implementations --- */
class CardPayment implements PaymentStrategy {
    @Override
    public boolean pay(int amount) {
        System.out.println("Paid by CARD: " + amount);
        return true;
    }
}

class UPIPayment implements PaymentStrategy {
    @Override
    public boolean pay(int amount) {
        System.out.println("Paid by UPI: " + amount);
        return true;
    }
}

class PaymentManager {
    private PaymentStrategy strategy;

    public PaymentManager(PaymentStrategy strategy) {
        this.strategy = strategy;
    }

    public boolean pay(int amount) {
        return strategy.pay(amount);
    }

    public void setStrategy(PaymentStrategy s) {
        this.strategy = s;
    }
}

/* --- Domain --- */
class User {
    final int id;
    final String name;

    User(int id, String name) {
        this.id = id;
        this.name = name;
    }
}

class Seat {
    final int id;
    final int row;
    final int column;
    final int price;

    public Seat(int id, int row, int column, int price) {
        this.id = id;
        this.row = row;
        this.column = column;
        this.price = price;
    }

    public int getId() {
        return id;
    }

    public int getPrice() {
        return price;
    }
}

/* --- SeatLock (transient hold) --- */
class SeatLock {
    final int seatId;
    final int userId;
    volatile Instant expiry;

    SeatLock(int seatId, int userId, Instant expiry) {
        this.seatId = seatId;
        this.userId = userId;
        this.expiry = expiry;
    }

    boolean isExpired() {
        return Instant.now().isAfter(expiry);
    }
}

/*
 * --- SeatManager: core concurrency & TTL handling for seats of a
 * show/auditorium ---
 */
class SeatManager {
    private final Map<Integer, Seat> seatsById;
    private final ConcurrentHashMap<Integer, SeatStatus> seatStatus;
    private final ConcurrentHashMap<Integer, SeatLock> seatLocks;
    private final Lock masterLock = new ReentrantLock();
    private final ScheduledExecutorService cleaner;
    private final long holdMinutes;

    public SeatManager(List<Seat> seats, long holdMinutes) {
        this.holdMinutes = holdMinutes;
        seatsById = seats.stream().collect(Collectors.toMap(Seat::getId, s -> s));
        seatStatus = new ConcurrentHashMap<>();
        seatLocks = new ConcurrentHashMap<>();
        for (Seat s : seats)
            seatStatus.put(s.getId(), SeatStatus.AVAILABLE);
        cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(this::cleanLocks, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Try to reserve (hold) seats for a user. Returns true if all seats are
     * successfully held.
     */
    public boolean reserveSeats(List<Integer> seatIds, User user) {
        // quick validation
        for (Integer id : seatIds) {
            if (!seatsById.containsKey(id))
                return false; // invalid seat
        }

        masterLock.lock();
        try {
            // check availability
            for (Integer id : seatIds) {
                SeatStatus st = seatStatus.get(id);
                if (st != SeatStatus.AVAILABLE)
                    return false;
            }
            // put holds
            Instant expiry = Instant.now().plus(holdMinutes, ChronoUnit.MINUTES);
            for (Integer id : seatIds) {
                seatStatus.put(id, SeatStatus.HOLD);
                SeatLock lock = new SeatLock(id, user.id, expiry);
                seatLocks.put(id, lock);
            }
            return true;
        } finally {
            masterLock.unlock();
        }
    }

    /**
     * Confirm seats (book them) for a user. This expects that seats are either held
     * by the same user or still available.
     * Returns true if seats were successfully confirmed.
     */
    public boolean confirmSeats(List<Integer> seatIds, User user) {
        masterLock.lock();
        try {
            // ensure all seats are either HOLD by this user or AVAILABLE (in case someone
            // bypassed hold)
            for (Integer id : seatIds) {
                SeatStatus st = seatStatus.get(id);
                if (st == SeatStatus.BOOKED)
                    return false; // already booked
                SeatLock lock = seatLocks.get(id);
                if (st == SeatStatus.HOLD) {
                    if (lock == null || lock.userId != user.id || lock.isExpired()) {
                        return false; // hold not owned or expired
                    }
                }
            }
            // mark booked and remove locks
            for (Integer id : seatIds) {
                seatStatus.put(id, SeatStatus.BOOKED);
                seatLocks.remove(id);
            }
            return true;
        } finally {
            masterLock.unlock();
        }
    }

    /**
     * Release holds for a user (explicit cancel).
     */
    public void releaseSeats(List<Integer> seatIds, User user) {
        masterLock.lock();
        try {
            for (Integer id : seatIds) {
                SeatLock lock = seatLocks.get(id);
                if (lock != null && lock.userId == user.id) {
                    seatLocks.remove(id);
                    seatStatus.put(id, SeatStatus.AVAILABLE);
                }
            }
        } finally {
            masterLock.unlock();
        }
    }

    /**
     * Safely cleanup expired locks (runs in background).
     * We use ConcurrentHashMap remove(key, value) to avoid races.
     */
    public void cleanLocks() {
        try {
            for (Map.Entry<Integer, SeatLock> e : seatLocks.entrySet()) {
                Integer id = e.getKey();
                SeatLock lk = e.getValue();
                if (lk.isExpired()) {
                    boolean removed = seatLocks.remove(id, lk);
                    if (removed) {
                        seatStatus.put(id, SeatStatus.AVAILABLE);
                        System.out.println("Released expired hold for seat " + id);
                    }
                }
            }
        } catch (Exception ex) {
            // log and continue
            ex.printStackTrace();
        }
    }

    public int calculateTotal(List<Integer> seatIds) {
        return seatIds.stream().mapToInt(id -> seatsById.get(id).getPrice()).sum();
    }

    public SeatStatus getSeatStatus(int seatId) {
        return seatStatus.get(seatId);
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}

/* --- BookingManager: orchestrates reserve + payment + confirm --- */
class BookingManager {
    private final PaymentManager paymentManager;
    private final SeatManager seatManager;

    public BookingManager(SeatManager seatManager, PaymentManager paymentManager) {
        this.seatManager = seatManager;
        this.paymentManager = paymentManager;
    }

    public boolean reserveSeats(User u, List<Integer> seatIds) {
        return seatManager.reserveSeats(seatIds, u);
    }

    /**
     * Confirm flow: 1) compute amount 2) attempt payment 3) on success confirm
     * seats.
     * If payment fails, seats remain on hold until expiry or explicit release.
     * Returns true if fully confirmed.
     */
    public boolean confirmSeats(User u, List<Integer> seatIds) {
        int total = seatManager.calculateTotal(seatIds);
        boolean paid = paymentManager.pay(total);
        if (!paid) {
            System.out.println("Payment failed for user " + u.id);
            return false;
        }
        boolean confirmed = seatManager.confirmSeats(seatIds, u);
        if (!confirmed) {
            System.out.println(
                    "Could not confirm seats after payment - initiating compensating action (refund) ideally.");
            // In real system: refund payment via payment gateway / record failure and
            // notify.
            return false;
        }
        System.out.println("Seats confirmed for user " + u.id);
        return true;
    }
}

/* --- Demo main to show flow --- */
public class BookMyShowDemo {
    public static void main(String[] args) throws InterruptedException {
        // create seats
        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 10; i++)
            seats.add(new Seat(i, i / 5 + 1, i % 5 + 1, 100 + (i % 3) * 50));

        SeatManager seatManager = new SeatManager(seats, 1); // 1 minute hold for demo
        PaymentManager pm = new PaymentManager(new UPIPayment());
        BookingManager bm = new BookingManager(seatManager, pm);

        User alice = new User(1, "Alice");
        List<Integer> pick = Arrays.asList(1, 2);

        System.out.println("Reserve attempt: " + bm.reserveSeats(alice, pick));
        System.out.println("Seat 1 status: " + seatManager.getSeatStatus(1));

        // confirm
        boolean result = bm.confirmSeats(alice, pick);
        System.out.println("Confirm result: " + result);
        System.out.println("Seat 1 status: " + seatManager.getSeatStatus(1));

        // cleanup and shutdown
        seatManager.shutdown();
    }
}
