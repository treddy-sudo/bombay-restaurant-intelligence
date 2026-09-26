package com.bombay.restaurantintelligence.intake;
import com.bombay.restaurantintelligence.domain.SourceType; import org.junit.jupiter.api.Test; import java.math.BigDecimal; import static org.assertj.core.api.Assertions.*;
class TextExtractorTest {private final TextExtractor extractor=new TextExtractor();
 @Test void parsesManualVendorPayment(){var r=extractor.extract("Paid Salman 6500 for vegetables",SourceType.MANUAL_TEXT,"1","owner");assertThat(r.fields()).containsEntry("vendor","Salman").containsEntry("amount","6500.00").containsEntry("description","vegetables");assertThat(r.confidence()).isEqualByComparingTo("0.98");}
 @Test void parsesRupeeAndCommaAmount(){var r=extractor.extract("Paid Salman ₹6,500 for vegetables",SourceType.MANUAL_TEXT,"1","owner");assertThat(new BigDecimal(r.fields().get("amount"))).isEqualByComparingTo("6500.00");}
 @Test void parsesEmployeeAdvance(){var r=extractor.extract("staff advance to Ravi 1500",SourceType.MANUAL_TEXT,"1","owner");assertThat(r.fields()).containsEntry("employee","Ravi").containsEntry("amount","1500.00");}
}
