package com.secureflow.secureflow.user;

import jakarta.persistence.*;
//DB테이블과 매핑되는 Entity->JPA는 Java객체와 관계형 데이터베이스를 연결하기 위한 표준 명세

//객체를 생성하는 것이 아닌->@Table(name="users")
@Entity
//이메일 중복을 데이터베이스 수준에서 방지
@Table(name = "users",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_user_email",
                columnNames = "email"
        )
    }
)


public class User {
    //기본키를 지정
    //GeneratedValue->기본키를 데이터베이스에 생성하도록 설정
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable=false)
    private String email;

    protected User(){

    }
    public User(String name,String email) {
        this.name=name;
        this.email=email;

    }

    public Long getId() {

        return id;
    }
    public String getEmail(){
        return email;

    }
    public String getName(){
        return name;

    }
    public void changeName(String name){
        this.name=name;

    }
}
