package ParkingLot;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

interface PaymentStrategy {
    public boolean pay(Double amount);
}

class UPIPayment implements PaymentStrategy {
    public boolean pay(Double amount) {
        System.out.println("Paid via UPI amount:" + amount);
        return true;
    }
}

class CardPayment implements PaymentStrategy {
    public boolean pay(Double amount) {
        System.out.println("Paid via card amount:" + amount);
        return true;
    }
}

enum ParkingStatus {
    AVAILABLE, UNAVAILABLE, HOLD
}

@ToString
enum ParkingSpotType {
    COMPACT("COMPACT", 15.0),
    LARGE("LARGE", 20.0),
    HANDICAP("HANDICAP", 10.0),
    ELECTIC("ELECTRIC", 40.0);

    private String type;
    private Double price;

    ParkingSpotType(String t, Double p) {
        type = t;
        price = p;
    }

    public String getType() {
        return type;
    }

    public Double getPrice() {
        return price;
    }
}

@Data
class ParkingSpots {
    int id;
    ParkingSpotType type;
    ParkingStatus status;

    ParkingSpots(int i, ParkingSpotType t) {
        id = i;
        status = ParkingStatus.AVAILABLE;
        type = t;
    }
}

@Data
@AllArgsConstructor
class ParkingLevel {
    List<ParkingSpots> spots;
    String name;
    int id;

    public ParkingSpots getAvailableSpot(ParkingSpotType type) {
        for (ParkingSpots pspot : spots) {
            if (pspot.getStatus() == ParkingStatus.AVAILABLE && pspot.getType() == type) {
                return pspot;
            }
        }
        return null;
    }
}

@AllArgsConstructor
@Data
class ParkingLot {
    int id;
    List<ParkingLevel> levels;
    String Location;
    String name;

    public ParkingSpots getAvailableSpot(ParkingSpotType type) {
        for (ParkingLevel level : levels) {
            ParkingSpots pspot = level.getAvailableSpot(type);
            if (pspot != null) {
                return pspot;
            }
        }
        return null;
    }
}

@AllArgsConstructor
@Data
class Vehicle {
    String vehicleNo;
    String type;
    int id;
}

@AllArgsConstructor
@Data
@ToString
class Ticket {
    int id;
    Vehicle v;
    Instant inTime;
    ParkingSpots spot;

    Double getPrice() {
        Double rate = spot.getType().getPrice();
        Duration diff = Duration.between(inTime, Instant.now());
        return rate * (diff.toMillis());
    }
}

class ReservationSystem {
    static ReservationSystem instance;
    final ParkingLot parkingLot;
    Map<Integer, Ticket> ticketMaster;
    AtomicInteger ticketId;
    Lock lock;

    private ReservationSystem(ParkingLot plot) {
        parkingLot = plot;
        ticketMaster = new ConcurrentHashMap<>();
        ticketId = new AtomicInteger(0);
        lock = new ReentrantLock();
    }

    public static ReservationSystem getInstance(ParkingLot plot) {
        if (instance == null) {
            synchronized (ReservationSystem.class) {
                if (instance == null) {
                    instance = new ReservationSystem(plot);
                }
            }
        }
        return instance;
    }

    Ticket reserveForVehicle(Vehicle v) {
        Ticket ticket = null;
        try {
            if (lock.tryLock(1, TimeUnit.MINUTES)) {
                ParkingSpots s = parkingLot.getAvailableSpot(ParkingSpotType.valueOf(v.getType()));
                if (s == null)
                    throw new Exception("No Available slots");
                s.setStatus(ParkingStatus.HOLD);
                ticket = new Ticket(ticketId.incrementAndGet(), v, Instant.now(), s);
                ticketMaster.put(ticketId.get(), ticket);
                return ticket;
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        } finally {
            lock.unlock();
        }
        return ticket;

    }

    Double unReserveForVehicle(Ticket t) {
        Double amount = 0.0;
        try {
            if (lock.tryLock(1, TimeUnit.MINUTES)) {

                ParkingSpots s = t.getSpot();
                if (s == null)
                    throw new Exception("no spots in ticket");
                s.setStatus(ParkingStatus.AVAILABLE);
                ticketMaster.remove(t.getId());
                amount = t.getPrice();
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());

        } finally {
            lock.unlock();
        }
        return amount;
    }

    boolean takePayment(Double amount, PaymentStrategy strategy) {
        return strategy.pay(amount);
    }
}

public class Attempt1 {
    public static void main(String[] args) {
        List<ParkingLevel> plevel = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            List<ParkingSpots> pSpots = new ArrayList<>();
            for (int j = 0; j < 4; j++) {
                pSpots.add(new ParkingSpots(i * 10 + j, ParkingSpotType.COMPACT));
                pSpots.add(new ParkingSpots(i * 10 + j, ParkingSpotType.LARGE));
                pSpots.add(new ParkingSpots(i * 10 + j, ParkingSpotType.ELECTIC));
                pSpots.add(new ParkingSpots(i * 10 + j, ParkingSpotType.HANDICAP));
            }
            plevel.add(new ParkingLevel(pSpots, "L" + i, i));
        }

        ParkingLot plot = new ParkingLot(0, plevel, "EGP", "MLCP");

        ReservationSystem reservationSystem = ReservationSystem.getInstance(plot);

        Vehicle v1 = new Vehicle("KA01", "COMPACT", 0);
        Vehicle v2 = new Vehicle("KA02", "COMPACT", 0);
        Vehicle v3 = new Vehicle("KA03", "COMPACT", 0);

        Ticket t = reservationSystem.reserveForVehicle(v1);
        System.out.println("Ticket reserved: " + t);

        Double amount = reservationSystem.unReserveForVehicle(t);
        System.out.println("Please pay amount: " + amount);

        if (reservationSystem.takePayment(amount, new UPIPayment())) {
            System.out.println("Amount paid via upi");
        } else {

            System.out.println("Failed please try again");
        }
    }

}
