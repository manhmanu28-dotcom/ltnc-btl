-- Bảng người dùng
CREATE TABLE IF NOT EXISTS users (
                                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                                     username TEXT UNIQUE NOT NULL,
                                     password_hash TEXT NOT NULL,
                                     role TEXT CHECK(role IN ('BIDDER', 'SELLER', 'ADMIN')) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );

-- Bảng sản phẩm đấu giá
CREATE TABLE IF NOT EXISTS items (
                                     id INTEGER PRIMARY KEY AUTOINCREMENT,
                                     seller_id INTEGER NOT NULL,
                                     title TEXT NOT NULL,
                                     description TEXT,
                                     starting_price REAL NOT NULL,
                                     category TEXT,
                                     FOREIGN KEY (seller_id) REFERENCES users(id)
    );

-- Bảng phiên đấu giá
CREATE TABLE IF NOT EXISTS auctions (
                                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                                        item_id INTEGER NOT NULL,
                                        current_price REAL NOT NULL,
                                        status TEXT CHECK(status IN ('OPEN', 'RUNNING', 'FINISHED', 'PAID', 'CANCELED')) DEFAULT 'OPEN',
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    FOREIGN KEY (item_id) REFERENCES items(id)
    );

-- Bảng lịch sử các lượt đặt giá
CREATE TABLE IF NOT EXISTS bid_transactions (
                                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                                auction_id INTEGER NOT NULL,
                                                bidder_id INTEGER NOT NULL,
                                                bid_amount REAL NOT NULL,
                                                bid_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                                FOREIGN KEY (auction_id) REFERENCES auctions(id),
    FOREIGN KEY (bidder_id) REFERENCES users(id)
    );