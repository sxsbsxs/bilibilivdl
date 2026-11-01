//
// Created by kafuu on 2025/6/4.
//


#include <memory>
#include <qrencode.h>

#include "app_qrcode.hpp"

QRCodeMatrix GeneralQrCode(const std::string &text) {
    std::unique_ptr<QRcode, void (*)(QRcode *)> qrcode(
            QRcode_encodeString(text.c_str(), 0, QR_ECLEVEL_M, QR_MODE_8, 1),
            &QRcode_free
    );
    // 创建二维码失败
    if (!qrcode) return {};
    // 取二维码宽度
    uint32_t size = qrcode->width;
    if (size == 0) return {};
    // 制取二维码矩阵
    std::vector<uint8_t> matrix(size * size, false);
    for (size_t i = 0; i < matrix.size(); ++i) {
        matrix[i] = (qrcode->data[i] & 0x1) != 0;
    }
    return {size, std::move(matrix)};
}

