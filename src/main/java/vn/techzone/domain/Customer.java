package vn.techzone.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** Customer profile exposed by the customer account aggregate. */
public record Customer(String id,String fullName,String email,String phoneNumber,String address,
                       String accountStatus,LocalDateTime createdAt,LocalDateTime updatedAt) {
    public Customer {
        Objects.requireNonNull(id);Objects.requireNonNull(fullName);Objects.requireNonNull(email);
        accountStatus=accountStatus==null?"ACTIVE":accountStatus;
    }
    public Customer updateProfile(String name,String phone,String newAddress) {
        if(name==null || name.isBlank() || newAddress==null) throw new IllegalArgumentException("Invalid customer profile");
        return new Customer(id,name.trim(),email,phone,newAddress,accountStatus,createdAt,LocalDateTime.now());
    }
}
