# TechZone Shop — bản web demo

Ứng dụng trình duyệt dùng Bootstrap 6.0.0-alpha.1 (tệp được lưu cục bộ), backend Java 17 và SQLite, phục vụ trình bày phân tích trong `TechZone_Report.docx`. Mặc định vào vai trò quản trị demo. Thanh toán, giao vận và nhà cung cấp là mô phỏng; dữ liệu sản phẩm khởi tạo là dữ liệu mẫu lưu trong SQLite. Vai trò là chế độ trình diễn trong một phiên cục bộ, không phải cơ chế bảo mật hay phân quyền nhiều người dùng.

Danh mục gồm 20 sản phẩm gốc và 200 linh kiện có tên, thông số và ảnh từ bộ dữ liệu [PCPartPicker](https://huggingface.co/datasets/ThigasFS/pcpartpicker-parts-dataset). Mỗi mục nhập có liên kết nguồn trong thông số. Giá EUR trong bộ dữ liệu được quy đổi cố định 30.000 VNĐ/EUR để demo, không phải giá hiện tại ở Việt Nam; tồn kho là giả lập và thời hạn bảo hành chưa xác minh. Lần đầu khởi chạy bản này, các mục mẫu bị trùng trước đây được ngừng bán nhưng vẫn được giữ để bảo toàn lịch sử, và sản phẩm mới được thêm vào SQLite hiện có.

Khi đã xác minh được thời hạn bảo hành, vào **Quản trị hệ thống → Danh mục → Cập nhật thời hạn bảo hành**, nhập số tháng và nguồn xác minh. Đơn đã giao khi thời hạn chưa rõ có thể gửi yêu cầu xác minh thủ công ở trang Bảo hành; sau khi cập nhật thông tin, chọn xác minh lại. Các bảo hành đã kích hoạt giữ nguyên thời hạn ban đầu.

Gợi ý ở trang chi tiết và giỏ hàng chỉ xếp hạng cặp có trong ma trận được duyệt, còn hàng và khớp thông số hiện tại; cặp chưa duyệt, thiếu dữ liệu, bị chặn hoặc đang rà soát không xuất hiện. Kỹ thuật có thể tạo yêu cầu rà soát cặp mới và ghi căn cứ trước khi duyệt; không cần sửa danh sách cứng trong mã. Ma trận khởi tạo vẫn là dữ liệu demo, không bảo đảm mọi cặp đã được hãng xác minh đầy đủ. Socket trùng không chứng minh phiên bản BIOS đang cài hỗ trợ CPU.

Nút **Tư vấn chọn máy** mở chatbot. Nhập mục đích rồi ngân sách (ví dụ “PC chơi game 35 triệu”); lịch sử chỉ giữ trong tab đang mở. [Qwen2.5-1.5B-Instruct](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) chạy cục bộ để tạo câu trả lời; BGE-M3 hỗ trợ hiểu nhu cầu và xếp hạng theo mô tả. Backend chỉ kết hợp các cặp được duyệt và còn khớp thông số trong SQLite. Giá, kho, thuế và giao hàng vẫn mô phỏng. Danh mục chưa đủ căn cứ xác nhận đầy đủ vỏ máy, kích thước, cáp nguồn hay BIOS. Khi mô hình chưa sẵn sàng hệ thống ghi rõ chế độ tư vấn cơ bản. Nếu chưa cài, chạy `setup-chat-ai.bat` (tải thêm khoảng 3,1 GB; lần đầu khởi động có thể chậm).

## Chạy trên Windows

1. Cài JDK 17 trở lên và Python nếu cần tính năng AI. Mở PowerShell trong thư mục dự án.
2. Chạy `.\setup-ai.bat` một lần để cài thư viện và tải các mô hình miễn phí, nếu máy chưa có `.venv-ai` và thư mục `models`.
3. Chạy `.\run.bat`. Trình duyệt sẽ mở địa chỉ `http://127.0.0.1:<cổng>` được in trong cửa sổ lệnh. Nếu trình duyệt không tự mở, sao chép địa chỉ đó. Để chương trình đang chạy trong lúc trình diễn; nhấn Ctrl+C trong cửa sổ lệnh để dừng.
4. Dùng Chrome hoặc Edge 130+, Firefox 132+ hoặc Safari 18+; Bootstrap 6 hiện là bản alpha. Cấp quyền micro cho trình duyệt khi dùng tìm kiếm giọng nói. Đóng backend cũ trước khi mở phiên mới. Không xóa `data/techzone.sqlite`: đây là cơ sở dữ liệu hiện tại. Trong Quản trị demo, trạng thái tìm kiếm AI tự cập nhật; BGE-M3 có thể mất thời gian khởi động trên CPU. Nếu báo chưa cài, chạy `.\setup-ai.bat`; nếu liên tục chờ thử lại, xem `data/semantic-search.log`.

## Cấu trúc và nâng cấp dữ liệu

Backend chia use case theo cổng nghiệp vụ; mỗi cổng có adapter hạ tầng riêng. `CatalogSearchService`, `CompatibilityPolicy`, `RecommendationEligibilityService`, `RecommendationRankingService` và `CheckoutWorkflowService` tách chính sách tìm kiếm, gợi ý, tương thích và điều phối checkout khỏi lớp lưu trạng thái. `TechZoneStore` giữ trạng thái phiên demo; một số orchestration giao vận, kho và bảo hành vẫn còn tập trung ở đây, nên chưa phải mỗi miền là một service độc lập như sơ đồ.

SQLite dùng schema quan hệ theo Table 20A: `products`, `product_variants`, `compatibility_rules`, `inventory_balances`, `inventory_units`, `shopping_carts`, `cart_items`, `sales_orders`, `order_items`, `unit_allocations`, `payments`, `shipments`, `warranties`, `rma_cases`, `interaction_events` và `recommendation_impressions`. Thông số nằm trong `product_variants.technical_specs` dưới dạng JSON TEXT như thiết kế; không có bảng khóa–giá trị `product_specs`. Trạng thái phiên và revision nằm ở `app_metadata`; dữ liệu phụ như rà soát tương thích, nhật ký giao nhận, thông báo và yêu cầu nhập được lưu bằng các bảng quan hệ riêng. Database đang chạy không có `app_state`, cột tương thích cũ hay view `inventory`/`order_lines`.

Khi mở database cũ còn bảng `app_state`, importer một lần đọc dữ liệu cũ, tạo bản sao `.pre-normalize-<timestamp>.bak`, rồi chuyển dữ liệu ánh xạ được sang schema quan hệ chuẩn trong file SQLite mới. Bản đang chạy không ghi snapshot; `LegacySqliteStore` chỉ phục vụ chuyển đổi cũ. Quá trình chuyển đổi kiểm tra transaction và khóa ngoại trước khi thay file. Giữ bản sao đến khi đã kiểm tra dữ liệu sau nâng cấp. Các dịch vụ thanh toán, giao vận và nhà cung cấp vẫn là mô phỏng, nên đây là bản demo theo cấu trúc quan hệ của báo cáo, chưa phải vận hành sản xuất.

## Các luồng trình diễn

- Danh mục, lọc thông số, so sánh, tìm bằng văn bản, giọng nói hoặc hình ảnh; chi tiết và gợi ý sản phẩm tương thích. BGE-M3, Whisper Small và SigLIP chạy cục bộ; bộ cài đầy đủ, gồm cả Qwen, tải khoảng 7 GB trọng số. Khi mô hình mới chưa sẵn sàng, hệ thống dùng MiniLM, Whisper Tiny hoặc ResNet-18 nếu đã có, và tìm kiếm có thể tạm dùng từ khóa trong lúc khởi tạo. Gợi ý kết hợp đồng xuất hiện, ngữ nghĩa và bộ lọc tương thích. Độ tương đồng ảnh không bảo đảm xác định đúng mã hàng.
- Giỏ, khuyến mãi và ba nhánh thanh toán mô phỏng; đơn hàng, xác minh, đối soát, cấp Serial, vận đơn, các mốc giao hàng và kích hoạt bảo hành.
- Kho, kiểm kê, ngưỡng tồn, đề nghị nhập và nhận hàng; nhà cung cấp mô phỏng. Tra cứu bảo hành, yêu cầu sửa chữa/RMA, xác minh thủ công và cập nhật xử lý.
- Báo cáo, đánh giá, rà soát tương thích, chỉnh thông số và ảnh, cấu hình gợi ý, đánh giá AI trên dữ liệu mẫu, quyền riêng tư, sự kiện và nhật ký. Việc đổi vai trò trong giao diện chỉ để xem các màn hình demo.

## Kiểm thử và giới hạn

Chạy `.\test.bat`. Các kiểm thử dùng cơ sở dữ liệu riêng; kiểm thử web dùng Chrome headless nếu Chrome được cài, và kiểm tra luồng đặt hàng tới RMA qua API. Cần tự kiểm tra micro, loa, giao diện và mô hình trên máy báo cáo. Các mô hình lớn hơn có thể khởi động và xử lý chậm trên CPU; cần khởi động trước buổi trình diễn. Các kết quả đánh giá AI dựa trên mẫu nhỏ, không chứng minh chất lượng trên khách hàng thực tế hay SLA. Bản demo chưa được kiểm thử tải hoặc bảo mật sản xuất; không sử dụng để xử lý thanh toán hay dữ liệu thật. Xem `docs/report-coverage.md` để đối chiếu với báo cáo.
# Giá GearVN và giới hạn dữ liệu

Trợ lý cấu hình dùng các quy tắc để trích ngân sách và lọc theo ma trận đã duyệt cùng thông số hiện có. Khi BGE-M3 (hoặc MiniLM dự phòng) sẵn sàng, hệ thống tính điểm gần ngữ nghĩa giữa mô tả nhu cầu và từng sản phẩm. Tìm kiếm giữ tối đa 256 phương án mỗi bước, do đó không bảo đảm phương án tối ưu toàn cục. Điểm không phải xác suất hoặc phép đo hiệu năng. Khi mô hình không trả kết quả kịp, giao diện báo chế độ dự phòng theo giá. Qwen sinh câu hội thoại, không trực tiếp quyết định cấu hình. Cặp khớp một thông số không được xem là kiểm định đầy đủ cho việc lắp máy.

Trong trang sản phẩm, nút **Cập nhật giá GearVN** đọc dữ liệu Product/Offer công khai trên các trang GearVN được đối chiếu thủ công. Cần có kết nối Internet và môi trường `.venv-ai` (chạy `setup-ai.bat` nếu chưa có). Giá được lưu trong bảng `market_offers`, kèm nguồn, trạng thái bán và thời điểm UTC; trên giao diện dữ liệu quá 24 giờ được đánh dấu cũ. Một lần cập nhật có thể chỉ thành công một phần, và khi lỗi giá cũ được giữ nguyên cùng thời điểm cũ. Chỉ bốn mã hiện được đối chiếu; những sản phẩm khác chưa có giá GearVN được xác minh. Không tự suy diễn số lượng tồn từ trạng thái bán.

Giá giao dịch, số lượng kho và thanh toán TechZone vẫn là dữ liệu mô phỏng; giá GearVN chỉ hiển thị riêng và không thay đổi giá trong giỏ, đơn hàng hay kho. Muốn vận hành bằng tồn thực phải kết nối và đối soát với hệ thống kho của cửa hàng. Không xem báo giá GearVN như cam kết giá của TechZone.

Tư vấn cấu hình và màn hình so sánh kiểm tra thêm chuẩn bo mạch/nguồn với vỏ, kích thước, đầu cáp và BIOS khi có dữ liệu cấu trúc kèm nguồn HTTPS. Thiếu thông số hoặc phiên bản BIOS thực tế được ghi là chưa xác minh; nguồn HTTPS là bằng chứng cần được con người đối chiếu, không tự chứng minh nội dung nguồn. Giỏ hàng chặn cặp chưa được duyệt, không khớp dữ liệu và xung đột vật lý đã biết. Dữ liệu vật lý và BIOS còn thiếu được cảnh báo nhưng chưa chặn đơn, nên chưa thể kết luận hoàn toàn khớp yêu cầu.
