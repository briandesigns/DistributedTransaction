// -------------------------------// Adapted from Kevin T. Manley// CSE 593// -------------------------------public class Car extends ReservableItem {    public Car(String location, int numCars, int carPrice) {        super(location, numCars, carPrice);    }    public String getKey() {        return Car.getKey(getLocation());    }    public static String getKey(String location) {        String s = "car-" + location;        return s.toLowerCase();    }}