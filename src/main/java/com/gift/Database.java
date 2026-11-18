package com.gift;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private static final String URL = "jdbc:sqlite:wishlist.db";

    public static void init() {
        String sql = """
            CREATE TABLE IF NOT EXISTS participants (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE NOT NULL,
                owner_pin TEXT NOT NULL,
                wishlist TEXT DEFAULT '',
                gifter_pin TEXT DEFAULT NULL
            );
        """;
        try (Connection conn = connect();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL);
    }
}