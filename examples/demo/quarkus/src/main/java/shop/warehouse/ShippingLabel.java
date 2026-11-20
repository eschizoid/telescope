package shop.warehouse;

/**
 * What the warehouse prints on a parcel: the order, who it goes to, and the carrier's tracking
 * number, empty until the order ships.
 *
 * @param number the order's number
 * @param recipient the customer's name
 * @param trackingNumber the carrier's tracking number, or {@code null} before the order ships
 */
public record ShippingLabel(String number, String recipient, String trackingNumber) {}
