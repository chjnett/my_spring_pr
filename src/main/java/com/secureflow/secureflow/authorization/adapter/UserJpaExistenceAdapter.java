package com.secureflow.secureflow.authorization.adapter;

import com.secureflow.secureflow.authorization.application.UserExistencePort;
import com.secureflow.secureflow.user.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class UserJpaExistenceAdapter implements UserExistencePort {

    private final UserRepository userRepository;

    public UserJpaExistenceAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean exists(Long userId) {
        return userRepository.existsById(userId);
    }
}
