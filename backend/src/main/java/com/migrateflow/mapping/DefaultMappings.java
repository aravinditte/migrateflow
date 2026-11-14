package com.migrateflow.mapping;

import com.migrateflow.mapping.MappingDefinition.FieldMapping;
import com.migrateflow.mapping.MappingDefinition.TransformStep;

import java.util.List;
import java.util.Map;

/** Default mappings implementing the target contracts in spec 4.2/4.3. */
public final class DefaultMappings {
    private DefaultMappings() {}

    public static MappingDefinition create() {
        List<FieldMapping> customers = List.of(
                new FieldMapping("Cust_No", "external_customer_id",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("UPPERCASE")), true),
                new FieldMapping("Customer_Name", "full_name",
                        List.of(TransformStep.of("TRIM")), true),
                new FieldMapping("Email", "email",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("LOWERCASE"),
                                TransformStep.of("NULL_IF_BLANK")), false),
                new FieldMapping("Join_Date", "joined_on",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("NULL_IF_BLANK"),
                                new TransformStep("PARSE_DATE", Map.of("pattern", "yyyy/MM/dd"))), false)
        );
        List<FieldMapping> orders = List.of(
                new FieldMapping("Order_No", "external_order_id",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("UPPERCASE")), true),
                new FieldMapping("Cust_No", "external_customer_id",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("UPPERCASE")), true),
                new FieldMapping("Order_Dt", "order_date",
                        List.of(TransformStep.of("TRIM"),
                                new TransformStep("PARSE_DATE", Map.of("pattern", "yyyy/MM/dd"))), true),
                new FieldMapping("Amount_JPY", "amount_yen",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("PARSE_LONG")), true),
                new FieldMapping("State", "status",
                        List.of(TransformStep.of("TRIM"), TransformStep.of("UPPERCASE"),
                                new TransformStep("ENUM_MAP", Map.of(
                                        "N", "NEW", "P", "PAID", "S", "SHIPPED", "C", "CANCELLED"))), true)
        );
        return new MappingDefinition(customers, orders);
    }

    /** Target contract descriptor whose hash is bound into every manifest. */
    public static String targetContract() {
        return "customers(external_customer_id:VARCHAR32:unique,full_name:VARCHAR200,email:VARCHAR254:nullable,"
                + "joined_on:DATE:nullable);orders(external_order_id:VARCHAR32:unique,"
                + "external_customer_id:FK,order_date:DATE,amount_yen:BIGINT>=0,status:NEW|PAID|SHIPPED|CANCELLED);v1";
    }
}
