//
// Created by kafuu on 2025/6/4.
//

#ifndef BILIDOWNLOAD_APP_QRCODE_HPP
#define BILIDOWNLOAD_APP_QRCODE_HPP

#include <vector>
#include <string>

struct QRCodeMatrix {
    uint32_t size = 0;
    std::vector<uint8_t> matrix;
};

QRCodeMatrix GeneralQrCode(const std::string &text);

#endif //BILIDOWNLOAD_APP_QRCODE_HPP
