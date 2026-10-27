package com.secureflow.secureflow.user;

import com.secureflow.secureflow.global.exception.DuplicateEmailException;
import com.secureflow.secureflow.global.exception.UserNotFoundException;
import com.secureflow.secureflow.user.dto.CreateUserRequest;
import com.secureflow.secureflow.user.dto.UpdateUserRequest;
import com.secureflow.secureflow.user.dto.UserResponse;

import jakarta.validation.Valid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        User savedUser = userRepository.save(user);

        return toResponse(savedUser);

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
}
