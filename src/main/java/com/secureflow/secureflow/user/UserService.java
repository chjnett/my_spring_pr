package com.secureflow.secureflow.user;

import com.secureflow.secureflow.global.exception.DuplicateEmailException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import com.secureflow.secureflow.user.dto.CreateUserRequest;
import com.secureflow.secureflow.user.dto.UpdateUserRequest;
import com.secureflow.secureflow.user.dto.UserResponse;

import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

@Service
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;

    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        User user = new User(
                request.name(),
                request.email()

        );
        try {
            // flush 시점에 UNIQUE 위반을 관찰해야 API 계약으로 번역할 수 있다.
            User savedUser = userRepository.saveAndFlush(user);
            return toResponse(savedUser);
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmailConstraint(exception)) {
                throw new DuplicateEmailException(request.email(), exception);
            }
            throw exception;
        }

    }

    //단일 회원 조회
    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return toResponse(user);

    }

    //전체 회원 조회
    //Transactional은
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();


    }

    //회원 삭제
    @Transactional
    public void deleteUser(Long id){
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        userRepository.delete(user);
    }
    //회원 수정
    @Transactional
    public UserResponse modifyUser(Long id, UpdateUserRequest request){
        User user=userRepository.findById(id)
                .orElseThrow(()->new UserNotFoundException(id));
        user.changeName(request.name());

        return toResponse(user);
    }


    //Entity -> DTO(변화)----->api용으로 사용하기 위해서

    private UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail()
        );

    }

    private boolean isDuplicateEmailConstraint(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("uk_user_email")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
