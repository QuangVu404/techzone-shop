# Đối chiếu bản web với TechZone_Report.docx

TechZone hiện là demo web chạy cục bộ với backend Java và SQLite. Vai trò được chuyển trong giao diện để trình diễn và mặc định là quản trị; đây không phải xác thực và RBAC nhiều người dùng. Dữ liệu khởi tạo, cổng thanh toán, hãng giao vận và nhà cung cấp là mô phỏng.

| Nhóm nghiệp vụ | Phạm vi trình diễn trên web |
| --- | --- |
| Khách hàng | Đăng ký, đăng nhập tài khoản demo; đồng ý hoặc từ chối ghi nhận tương tác |
| Danh mục | Danh sách, lọc, so sánh, chi tiết, chỉnh thông số và ảnh |
| AI | Tìm kiếm BGE-M3, tìm bằng ảnh SigLIP, nhận giọng nói Whisper Small khi đã cài; có mô hình cũ dự phòng. Gợi ý kết hợp giỏ mẫu, ngữ nghĩa và lọc tương thích; fallback, sự kiện khi đồng ý và đánh giá bằng dữ liệu mẫu |
| Bán hàng | Giỏ, mã ưu đãi, kiểm tra cấu hình và tồn, ba kết quả thanh toán mô phỏng, đơn hàng, xác minh và ghi chú tư vấn |
| Kho và giao vận | Ngưỡng tồn, đề nghị nhập, nhận hàng, kiểm kê, nhà cung cấp mô phỏng, cấp Serial, vận đơn và hành trình mô phỏng |
| Bảo hành | Kích hoạt sau giao thành công, tra cứu Serial, RMA, sửa chữa ngoài bảo hành, yêu cầu xác minh và cập nhật xử lý |
| Quản lý | Báo cáo theo nhóm, đánh giá, rà soát tương thích, thông báo, cấu hình gợi ý và nhật ký |

Cần phân biệt luồng demo với triển khai vận hành: SQLite hiện lưu dữ liệu nghiệp vụ bằng các bảng quan hệ và revision trong `app_metadata`; không còn ghi `app_state` snapshot. Tài khoản/vai trò vẫn là chế độ demo, cổng thanh toán, giao vận và nhà cung cấp là mô phỏng; thông số sản phẩm và ma trận tương thích khởi tạo chưa được xác minh đầy đủ theo hãng. Chưa chứng minh các chỉ tiêu SLA, 99,9% sẵn sàng, 1.000 người dùng đồng thời hoặc chất lượng AI trên dữ liệu khách hàng thật. Việc kiểm tra micro và hiển thị trên máy báo cáo vẫn cần thực hiện trực tiếp.

Đối chiếu thiết kế: SQLite hiện triển khai 17 bảng nghiệp vụ chính theo tên và cột của Table 20A, gồm `products`, `product_variants`, `compatibility_rules`, `inventory_balances`, `inventory_units`, `shopping_carts`, `cart_items`, `sales_orders`, `order_items`, `unit_allocations`, `payments`, `shipments`, `warranties`, `rma_cases`, `interaction_events` và `recommendation_impressions`. Bảng hỗ trợ được chuẩn hóa riêng để lưu các luồng demo bổ sung. Không còn các cột/bảng/view tương thích cũ trong database đang chạy; SKU được giữ quan hệ sản phẩm–biến thể một-một khi nạp dữ liệu cũ, không tự gộp sản phẩm gần tên.

`RelationalSchema` tạo schema quan hệ trực tiếp theo Table 20A; `product_variants.technical_specs` là JSON TEXT đúng kiểu minh họa trong báo cáo. Không có `product_specs` key–value, trigger đồng bộ cột cũ hay snapshot SQLite. `app_metadata` giữ revision và khách hàng đang chọn; bảng phụ nghiệp vụ được lưu quan hệ riêng. `LegacySqliteStore` chỉ chạy khi nâng cấp database còn `app_state`: tạo backup, đọc snapshot cũ rồi chuyển sang schema chuẩn. Sau migration, importer không tham gia luồng đọc/ghi thông thường. Trường lịch sử không có căn cứ không được tự dựng thành dữ kiện đã xác minh.

Tầng ứng dụng có các use case tìm kiếm, gợi ý, checkout, cấp Serial và bảo hành. Mỗi use case nhận cổng nghiệp vụ riêng với adapter hạ tầng tương ứng; tìm kiếm, quy tắc tương thích, lọc ứng viên gợi ý, xếp hạng gợi ý và điều phối checkout được đặt trong các service riêng. `TechZoneStore` giữ state phiên demo và cung cấp thao tác đọc/ghi cho các service. Một số orchestration giao vận, kho và bảo hành vẫn nằm trong lớp này; ranh giới tầng và cổng đã rõ hơn, nhưng chưa tách mọi workflow thành service/repository độc lập như Figure 13.

Giỏ hàng dùng `ShoppingCart`; `InventoryBalance` kiểm tra các chuyển trạng thái giữ, cấp và hoàn tồn kho. SQLite áp dụng khóa duy nhất cho số đơn và idempotency key. Các bảng lõi lưu khóa định danh, quan hệ và thuộc tính theo Table 20A; những cột bổ sung được dùng cho workflow demo như trạng thái thanh toán, vận chuyển và kiểm soát tồn. Những trường nghiệp vụ không có nguồn dữ liệu thực, chẳng hạn capture từ cổng thanh toán thật, không được gán giả.

Các dòng đơn và cấp phát có định danh và khóa liên kết dòng đơn–serial–biến thể; thanh toán và vận chuyển có các trường theo thiết kế. Khi quét serial mới, người thực hiện và thời điểm quét được lưu trong bảng quan hệ và phục hồi sau khởi động. Dữ liệu cũ không có nguồn vẫn được giữ thiếu/null thay vì suy đoán. Java serialization còn được dùng cho payload phiên localhost và để đọc database cũ trong importer; SQLite đang chạy chỉ lưu các hàng quan hệ, không lưu blob snapshot.

Các lớp `Customer`, `ShoppingCart`, `SearchRequest`, `RecommendationList`, `Payment`, `Shipment` và `UnitAllocation` tham gia các luồng tương ứng. `OrderLine` và `RmaCase` còn giữ tên lớp cũ để giải mã payload phiên/importer cũ; SQLite không dùng serialized object làm nguồn lưu trữ. Xác nhận/huỷ đơn, giao hàng, bảo hành và RMA gọi quy tắc miền; giảm giá được phân bổ theo từng dòng đơn, giá được chụp khi thêm sản phẩm, và hồ sơ khách hàng có API cập nhật.

UC12: chi tiết và giỏ hàng gọi API gợi ý cho SKU được chọn. Backend chỉ xếp hạng cặp có trong ma trận duyệt, còn hàng, phù hợp giỏ và khớp thông số hiện tại; kết quả giới hạn theo cấu hình. Cặp mới có thể được gửi cho nhân viên rà soát thay vì thêm cố định trong mã. Ma trận ban đầu vẫn là dữ liệu demo, không chứng minh nguồn hãng hay toàn bộ điều kiện lắp ráp. Mô hình đồng xuất hiện/ngữ cảnh dùng giỏ mẫu và dữ liệu được đồng ý, còn BGE-M3 là tín hiệu tùy chọn. Fallback chỉ xét tập đã lọc; không có ứng viên thì bỏ gợi ý. Chưa kiểm chứng SLA NFR02 dưới tải thực.

FR10: giỏ từ chối cặp thuộc loại cần kiểm tra nếu chưa được duyệt hoặc không còn khớp thông số, và chặn xung đột vật lý đã biết. Màn hình so sánh hiển thị kết quả cặp và các kiểm tra vỏ/cáp/BIOS khi có dữ liệu; những trường thiếu tiếp tục ghi chưa xác minh. Điều kiện vỏ, cáp và BIOS chưa được kiểm tra đầy đủ khi xác nhận đơn nên chưa thể kết luận hoàn toàn khớp yêu cầu.

Chatbot tư vấn cấu hình là phần mở rộng theo yêu cầu bổ sung, không phải một use case được đặc tả riêng trong báo cáo. Qwen tạo hội thoại, BGE-M3 hỗ trợ hiểu và xếp hạng, còn thông tin bán hàng cùng các cặp tương thích được kiểm tra từ dữ liệu ứng dụng. Cả hai mô hình có thể chậm hoặc trả lời sai; chế độ dự phòng được ghi rõ trong giao diện.
