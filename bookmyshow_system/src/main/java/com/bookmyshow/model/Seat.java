package com.bookmyshow.model;

public class Seat {
    private final String seatId;
    private final String row;
    private final int number;
    private final String section;
    private final double price;

    public Seat(String seatId, String row, int number, String section, double price) {
        this.seatId = seatId;
        this.row = row;
        this.number = number;
        this.section = section;
        this.price = price;
    }

    public String getSeatId() { return seatId; }
    public String getRow() { return row; }
    public int getNumber() { return number; }
    public String getSection() { return section; }
    public double getPrice() { return price; }

    @Override
    public String toString() {
        return String.format("%s (%s, $%.0f)", seatId, section, price);
    }
}
