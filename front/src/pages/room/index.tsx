import React, { useEffect, useContext } from 'react';
import { GameStatusContext } from 'store/gameStatus';
import { Emitter } from 'controllers/Emitter';
import { Receiver } from 'controllers/Receiver';
import { WSConnection } from 'connections/WSConnection';

import './room.scss';

import { ViewStatus } from '../../types/ViewStatus';
import { QuizPanel } from '../../components/QuizPanel';
import { expressRoomStatus } from './expressRoomStatus';
import { QuestionModal } from '../../components/QuestionModal';

// FIXME: 仕様よう分かっとらんでな、あとで直す
type User = {
  name: string;
  starNumber: number;
  input: string;
};

const Room: React.FC = () => {
  const status: ViewStatus = ViewStatus.WAITING_QUESTION as ViewStatus;
  const users: User[] = [];
  const questionBody = '';
  const answerBody = '';
  const isAnswerFinished = false;
  const onInputAnswer: (input: string) => void = (input) => console.log(input);
  const onSubmitAnswer: React.MouseEventHandler = (e) => console.log(e);

  const gameStatus = useContext(GameStatusContext);

  useEffect(() => {
    // TODO: URLをちゃんとしたのに直す
    const connection = new WSConnection('wss://hogehoge.com');
    const receiver = new Receiver(gameStatus.dispatch);
    connection.setReceivers(receiver);
    const emitter = connection.createEmitter();

    gameStatus.dispatch({
      type: 'INIT_CONTROLLERS',
      payload: {
        controllers: {
          emitter,
          receiver,
        },
      },
    });
  }, []);

  return (
    <div className="Room__view">
      {status === ViewStatus.WAITING_ANSWER && !isAnswerFinished ? (
        <QuestionModal
          questionBody={questionBody}
          remainTime={0}
          answerBody={answerBody}
          onInputAnswer={onInputAnswer}
          onSubmitAnswer={onSubmitAnswer}
        />
      ) : null}
      <p>{expressRoomStatus(status)}</p>
      {questionBody !== '' ? (
        <p className="Room__QuestionBox">{questionBody}</p>
      ) : null}
      {users.map((user) => (
        <QuizPanel
          key={user.name}
          name={user.name}
          starNumber={user.starNumber}
          answerText={user.input}
        />
      ))}
    </div>
  );
};

export default Room;
