# TechZone Shop — bản web demo

Ứng dụng trình duyệt dùng Bootstrap 6.0.0-alpha.1 (tệp lưu cục bộ), backend Java 17 và SQLite, phục vụ trình bày phân tích trong `TechZone_Report.docx`. Catalogue khởi tạo dùng snapshot 200 sản phẩm crawl từ trang công khai GearVN; các giao dịch TechZone, thanh toán, giao vận và kho nội bộ vẫn là mô phỏng. Vai trò là chế độ trình diễn trong một phiên cục bộ, không phải cơ chế bảo mật hay phân quyền nhiều người dùng.

Danh mục khởi tạo gồm 200 mặt hàng từ `https://gearvn.com/collections/linh-kien-may-tinh`. Snapshot được crawl ngày 12/11/2026, gồm tên, giá niêm yết/giá khuyến mãi nếu có, ảnh, URL nguồn và thời điểm lấy. Crawler dùng Python standard library, đọc trang HTML công khai, tuân theo `robots.txt`, giới hạn tốc độ và không gọi `/api/`. Tình trạng bán không thể hiện trên danh sách được ghi `UNKNOWN`; tồn kho TechZone không được suy ra từ GearVN. Khi khởi động, hệ thống bổ sung một lần tồn kho mô phỏng lên ít nhất 100 đơn vị cho mỗi sản phẩm đang hoạt động. Các mục catalogue cũ vẫn được lưu để bảo toàn đơn hàng/lịch sử nhưng được ngừng hiển thị.

Khi đã xác minh được thời hạn bảo hành, vào **Quản trị hệ thống → Danh mục → Cập nhật thời hạn bảo hành**, nhập số tháng và nguồn xác minh. Đơn đã giao khi thời hạn chưa rõ có thể gửi yêu cầu xác minh thủ công ở trang Bảo hành; sau khi cập nhật thông tin, chọn xác minh lại. Các bảo hành đã kích hoạt giữ nguyên thời hạn ban đầu.

Gợi ý ở trang chi tiết và giỏ hàng chỉ xếp hạng cặp có trong ma trận được duyệt, còn hàng và khớp thông số hiện tại; cặp chưa duyệt, thiếu dữ liệu, bị chặn hoặc đang rà soát không xuất hiện. Kỹ thuật có thể tạo yêu cầu rà soát cặp mới và ghi căn cứ trước khi duyệt; không cần sửa danh sách cứng trong mã. Ma trận khởi tạo vẫn là dữ liệu demo, không bảo đảm mọi cặp đã được hãng xác minh đầy đủ. Socket trùng không chứng minh phiên bản BIOS đang cài hỗ trợ CPU.

Nút **Tư vấn chọn máy** mở chatbot. Nhập mục đích rồi ngân sách (ví dụ “PC chơi game 35 triệu”); lịch sử chỉ giữ trong tab đang mở. [Qwen3-4B-Instruct-2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507) chạy cục bộ để tạo câu trả lời; BGE-M3 hỗ trợ hiểu nhu cầu và xếp hạng theo mô tả. Backend chỉ kết hợp các cặp được duyệt và còn khớp thông số trong SQLite. Giá, kho, thuế và giao hàng vẫn mô phỏng. Danh mục chưa đủ căn cứ xác nhận đầy đủ vỏ máy, kích thước, cáp nguồn hay BIOS. Khi mô hình chưa sẵn sàng hệ thống ghi rõ chế độ tư vấn cơ bản. Nếu chỉ cần tải chatbot, chạy `setup-chat-ai.bat` sau khi cài môi trường AI.

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

- Danh mục, lọc thông số, so sánh, tìm bằng văn bản, giọng nói hoặc hình ảnh; chi tiết và gợi ý sản phẩm tương thích. Bộ cài ưu tiên BGE-M3, Whisper Medium, SigLIP 2 Base và Qwen3 4B chạy cục bộ. Ba mô hình nâng cấp tải khoảng 12,6 GB, chưa tính BGE-M3 và thư viện; Qwen3 có thể cần trên 16 GB RAM khi chạy CPU và xử lý chậm. Cần Internet lúc tải mô hình. Nếu đã cài trước đó, Qwen 2.5 1.5B, Whisper Small/Tiny và SigLIP/ResNet-18 là phương án dự phòng; tìm kiếm văn bản có thể dùng từ khóa khi BGE-M3 chưa sẵn sàng. Độ tương đồng ảnh không bảo đảm xác định đúng mã hàng.
- Giỏ, khuyến mãi và ba nhánh thanh toán mô phỏng; đơn hàng, xác minh, đối soát, cấp Serial, vận đơn, các mốc giao hàng và kích hoạt bảo hành.
- Kho, kiểm kê, ngưỡng tồn, đề nghị nhập và nhận hàng; nhà cung cấp mô phỏng. Tra cứu bảo hành, yêu cầu sửa chữa/RMA, xác minh thủ công và cập nhật xử lý.
- Báo cáo, đánh giá, rà soát tương thích, chỉnh thông số và ảnh, cấu hình gợi ý, đánh giá AI trên dữ liệu mẫu, quyền riêng tư, sự kiện và nhật ký. Việc đổi vai trò trong giao diện chỉ để xem các màn hình demo.

## Kiểm thử và giới hạn

Chạy `.\test.bat`. Các kiểm thử dùng cơ sở dữ liệu riêng; kiểm thử web dùng Chrome headless nếu Chrome được cài, và kiểm tra luồng đặt hàng tới RMA qua API. Cần tự kiểm tra micro, loa, giao diện và mô hình trên máy báo cáo. Các mô hình lớn hơn có thể khởi động và xử lý chậm trên CPU; cần khởi động trước buổi trình diễn. Các kết quả đánh giá AI dựa trên mẫu nhỏ, không chứng minh chất lượng trên khách hàng thực tế hay SLA. Bản demo chưa được kiểm thử tải hoặc bảo mật sản xuất; không sử dụng để xử lý thanh toán hay dữ liệu thật.
# Dữ liệu crawl và giới hạn demo

Danh mục lưu giá, URL, trạng thái công khai nếu trang danh mục cung cấp và thời điểm UTC vào SQLite. Backend tải ảnh từ CDN công khai GearVN và cache cục bộ khi hiển thị; cần Internet để tải lần đầu.

Giá/khuyến mãi và tình trạng bán là dữ liệu công khai từ GearVN tại thời điểm crawl, không phải cam kết giá hay tồn kho của TechZone. Tồn kho nội bộ, đơn hàng, thanh toán, thuế và vận chuyển vẫn là mô phỏng; muốn dùng tồn thực cần kết nối hệ thống kho của nhà bán. Các trang listing không cung cấp đủ thông số kỹ thuật; các thông số và tương thích chưa có nguồn xác minh không được coi là đã kiểm chứng.

Tư vấn và gợi ý vẫn tuân theo các quy tắc tương thích đã duyệt trong dữ liệu TechZone. Mặt hàng GearVN mới không tự được duyệt cặp tương thích, và tồn kho TechZone không được gán tự động; do đó chức năng tư vấn máy có thể không đề xuất cấu hình cho catalogue mới cho đến khi các cặp và tồn kho được xác minh/đồng bộ riêng.
# Danh mục và gợi ý từ GearVN

Danh mục GearVN là dữ liệu thu thập từ nguồn bên ngoài, có thể tìm theo từ khóa hoặc ngữ nghĩa. Tồn kho 100 đơn vị được khởi tạo riêng để trình diễn, không phản ánh tồn thực của cửa hàng nguồn; các giao dịch sau đó thay đổi tồn kho bình thường. Tìm kiếm hình ảnh chỉ xét các ảnh đã được lưu trong bộ đệm hoặc chỉ mục.

Snapshot ngày 12/11/2026 gồm 200 sản phẩm: 167 trang có bảng thông số cấu trúc với 3.014 mục; 33 trang còn lại có 194 đặc điểm trích nguyên văn từ nội dung trang, không được coi là bảng thông số. Crawler `python ai/gearvn_catalog.py --output target/gearvn-catalog.tsv --limit 200 --delay 0.5 --details` đọc các trang công khai theo robots.txt, lưu riêng trạng thái bảng và chỉ ghi snapshot sau khi có đủ 200 bản ghi. Ảnh được tải nền và số ảnh đã lập chỉ mục được báo khi tìm kiếm. Gợi ý sản phẩm cùng loại dùng BGE-M3 để xếp hạng theo mô tả; gợi ý này không xác nhận tương thích linh kiện. Các bộ cấu hình vẫn cần dữ liệu tương thích được duyệt và không được tự suy ra từ mô tả.

Lần rà soát danh mục chuẩn hóa loại hàng, tên hãng và những giá trị socket/loại RAM rõ ràng từ trường nguồn. Danh sách từng sản phẩm và các mâu thuẫn phát hiện được lưu tại `docs/catalog-quality-audit.tsv`. Trạng thái không phát hiện mâu thuẫn chỉ phản ánh các kiểm tra tự động, không có nghĩa mọi thông số đã được nhà sản xuất xác minh. Các mục đối chiếu trực tiếp với tài liệu hãng được ghi kèm nguồn trong `ai/verified_product_facts.json`.

Chi tiết sản phẩm có thêm gợi ý linh kiện tham khảo: hệ thống loại các xung đột đã biết, đối chiếu phần thông số hiện có và dùng BGE-M3 xếp hạng khi mô hình sẵn sàng. “Khớp một phần” chỉ áp dụng cho điều kiện được nêu; “Chưa đủ thông tin” cần kiểm tra thêm. Đây không phải cặp đã duyệt: kiểm tra tương thích và điều kiện đặt hàng vẫn áp dụng như trước.

Mục “Sản phẩm cùng loại” hiển thị các sản phẩm khác trong cùng danh mục, bao gồm sản phẩm hiện hết hàng. Danh sách xuất hiện khi mô hình chưa sẵn sàng và chuyển sang thứ tự BGE-M3 khi có kết quả; nút “Xem thêm” cho phép xem toàn bộ danh mục tương ứng. Đây là các lựa chọn cùng loại, không phải kết luận tương thích.

Trợ lý có thể giới thiệu sản phẩm GearVN riêng lẻ. Khi chưa có đủ thông số và các cặp tương thích đã xác minh để lập cấu hình, kết quả ngữ nghĩa chỉ là sản phẩm tham khảo, không phải bộ linh kiện được xác nhận tương thích. Thanh toán, vận chuyển và vai trò trong bản chạy cục bộ vẫn có giới hạn mô phỏng.
