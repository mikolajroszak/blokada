//
//  This file is part of Blokada.
//
//  This Source Code Form is subject to the terms of the Mozilla Public
//  License, v. 2.0. If a copy of the MPL was not distributed with this
//  file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
//  Copyright © 2021 Blocka AB. All rights reserved.
//
//  @author Karol Gusak
//

import Foundation
import Combine
import UIKit

// Contains "main app state" mostly used in Home screen.
class AppRepo: Startable {

    var appStateHot: AnyPublisher<AppState, Never> {
        self.writeAppState.compactMap { $0 }.removeDuplicates().eraseToAnyPublisher()
    }

    var workingHot: AnyPublisher<Bool, Never> {
        self.writeWorking.compactMap { $0 }.removeDuplicates().eraseToAnyPublisher()
    }

    var pausedUntilHot: AnyPublisher<Date?, Never> {
        self.writePausedUntil.removeDuplicates().eraseToAnyPublisher()
    }

    var accountTypeHot: AnyPublisher<AccountType, Never> {
        self.writeAccountType.compactMap { $0 }.removeDuplicates().eraseToAnyPublisher()
    }

    private lazy var timer = Services.timer

    private lazy var currentlyOngoingHot = Repos.processingRepo.currentlyOngoingHot
    private lazy var accountHot = Repos.accountRepo.accountHot
    private lazy var cloudRepo = Repos.cloudRepo

    fileprivate let writeAppState = CurrentValueSubject<AppState?, Never>(nil)
    fileprivate let writeWorking = CurrentValueSubject<Bool?, Never>(nil)
    fileprivate let writePausedUntil = CurrentValueSubject<Date?, Never>(nil)
    fileprivate let writeAccountType = CurrentValueSubject<AccountType?, Never>(nil)

    fileprivate let pauseAppT = Tasker<Date?, Ignored>("pauseApp")
    fileprivate let unpauseAppT = SimpleTasker<Ignored>("unpauseApp")

    private var cancellables = Set<AnyCancellable>()
    private let recentAccountType = Atomic<AccountType>(AccountType.Libre)

    func start() {
        onPauseApp()
        onUnpauseApp()
        onAnythingThatAffectsAppState_UpdateIt()
        onAccountChange_UpdateAccountType()
        onCurrentlyOngoing_ChangeWorkingState()
        onPause_WaitForExpirationToUnpause()
        loadPauseTimerState()
        emitWorkingStateOnStart()
    }

    func pauseApp(until: Date?) -> AnyPublisher<Ignored, Error> {
        return pauseAppT.send(until)
    }

    func unpauseApp() -> AnyPublisher<Ignored, Error> {
        return unpauseAppT.send()
    }

    // App can be paused with a timer (Date), or indefinitely (nil)
    private func onPauseApp() { // TODO: also pause plus
        pauseAppT.setTask { until in
            return self.appStateHot.first()
            .flatMap { it -> AnyPublisher<Ignored, Error> in
                // App is already paused, only update timer
                if it == .Paused {
                    guard let until = until else {
                        // Pause indefinitely instead
                        return self.timer.cancelTimer(NOTIF_PAUSE)
                    }

                    return self.timer.createTimer(NOTIF_PAUSE, when: until)
                } else if it == .Activated {
                    guard let until = until else {
                        // Just pause indefinitely
                        return self.cloudRepo.setPaused(true)
                    }

                    return self.cloudRepo.setPaused(true)
                    .flatMap { _ in self.timer.createTimer(NOTIF_PAUSE, when: until) }
                    .eraseToAnyPublisher()
                } else {
                    return Fail(error: "cannot pause, app in wrong state")
                    .eraseToAnyPublisher()
                }
            }
            .map { _ in
                self.writePausedUntil.send(until)
                return true
            }
            .eraseToAnyPublisher()
        }
    }

    private func onUnpauseApp() {
        unpauseAppT.setTask { _ in
            return self.appStateHot.first()
            .flatMap { it -> AnyPublisher<Ignored, Error> in
                if it == .Paused || it == .Deactivated {
                    return self.cloudRepo.setPaused(false)
                    .flatMap { _ in self.timer.cancelTimer(NOTIF_PAUSE) }
                    .eraseToAnyPublisher()
                } else {
                    return Fail(error: "cannot unpause, app in wrong state")
                    .eraseToAnyPublisher()
                }
            }
            .map { _ in
                self.writePausedUntil.send(nil)
                return true
            }
            .eraseToAnyPublisher()
        }
    }

    private func onAnythingThatAffectsAppState_UpdateIt() {
        Publishers.CombineLatest3(
            accountTypeHot,
            cloudRepo.dnsProfileActivatedHot,
            cloudRepo.adblockingPausedHot
        )
        .map { it -> AppState in
            let (accountType, dnsProfileActivated, adblockingPaused) = it

            if !accountType.isActive() {
                return AppState.Deactivated
            }

            if adblockingPaused {
                return AppState.Paused
            }

            if dnsProfileActivated {
                return AppState.Activated
            }

            return AppState.Deactivated
        }
        .sink(onValue: { it in self.writeAppState.send(it) })
        .store(in: &cancellables)
    }

    private func onAccountChange_UpdateAccountType() {
        accountHot.compactMap { it in it.account.type }
        .map { it in mapAccountType(it) }
        .sink(onValue: { it in
            self.recentAccountType.value = it
            self.writeAccountType.send(it)
        })
        .store(in: &cancellables)
    }

    private func onCurrentlyOngoing_ChangeWorkingState() {
        let tasksThatMarkWorkingState = Set([
            "accountInit", "refreshAccount", "restoreAccount",
            "pauseApp", "unpauseApp",
            "newPlus", "clearPlus", "switchPlusOn", "switchPlusOff",
            "consumePurchase"
        ])

        currentlyOngoingHot
        .map { it in Set(it.map { $0.component }) }
        .map { it in !it.intersection(tasksThatMarkWorkingState).isEmpty }
        .sink(onValue: { it in self.writeWorking.send(it) })
        .store(in: &cancellables)
    }

    private func onPause_WaitForExpirationToUnpause() {
        pausedUntilHot
        .compactMap { $0 }
        .flatMap { _ in
            // This cold producer will finish once the timer expires.
            // It will error out whenever this timer is modified.
            self.timer.obtainTimer(NOTIF_PAUSE)
        }
        .flatMap { _ in
            self.unpauseApp()
        }
        .sink(
            onFailure: { err in Logger.w("AppRepo", "Pause timer failed: \(err)")}
        )
        .store(in: &cancellables)
    }

    private func loadPauseTimerState() {
        timer.getTimerDate(NOTIF_PAUSE)
        .tryMap { it in self.writePausedUntil.send(it) }
        .sink()
        .store(in: &cancellables)
    }

    private func emitWorkingStateOnStart() {
        writeAppState.send(.Deactivated)
        writeWorking.send(true)
    }
}

func getDateInTheFuture(seconds: Int) -> Date {
    let date = Date()
    var components = DateComponents()
    components.setValue(seconds, for: .second)
    let dateInTheFuture = Calendar.current.date(byAdding: components, to: date)
    return dateInTheFuture!
}

class DebugAppRepo: AppRepo {

    private let log = Logger("App")
    private var cancellables = Set<AnyCancellable>()

    override func start() {
        super.start()

        writeAppState.sink(
            onValue: { it in
                self.log.v("App state: \(it)")
            }
        )
        .store(in: &cancellables)
    }

}
